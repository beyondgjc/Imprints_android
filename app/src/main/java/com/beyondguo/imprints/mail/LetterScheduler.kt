package com.beyondguo.imprints.mail

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beyondguo.imprints.data.FutureLetterEntity
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.LetterRepository
import com.beyondguo.imprints.data.MediaFiles
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 送达调度（04 §4）。
 *
 * **数据库是事实来源**，AlarmManager / WorkManager 都只是可重建的执行机制：
 * - 封存/改期：事务提交后撤销旧任务，按新 scheduleRevision 注册；
 * - 执行器：重新查库，校验 id / 版本 / 删除 / 提醒开关 / 是否到期已读，旧版本立即退出；
 * - 启动、重启、系统时间/时区变化、应用更新后对账重建；
 * - 通知能力允许时投递通用提醒（[LetterNotifier]）；首页读取不依赖 postedAtMs。
 */
object LetterScheduler {
    const val ACTION_LETTER_DUE = "com.beyondguo.imprints.LETTER_DUE"
    private const val EXTRA_ENTRY_ID = "entryId"
    private const val EXTRA_REVISION = "revision"
    private const val WORK_NAME = "imprints-letter-reconcile"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否可用精确提醒（Android 31+ 需要「闹钟和提醒」授权；29/30 无需特殊授权分支）。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** 未获精确提醒授权时展示「普通提醒，后台通知可能延迟」，并提供设置入口。 */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    private fun alarmPendingIntent(context: Context, entryId: String, revision: Int): PendingIntent {
        val intent = Intent(context, LetterAlarmReceiver::class.java)
            .setAction(ACTION_LETTER_DUE)
            .putExtra(EXTRA_ENTRY_ID, entryId)
            .putExtra(EXTRA_REVISION, revision)
        // 请求码含版本：改期后旧 PendingIntent 不会命中新任务
        return PendingIntent.getBroadcast(
            context,
            (entryId.hashCode() and 0x0FFFFFFF) xor revision,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 注册一封信的送达提醒；notifyEnabled=false 时不设闹钟（到期置顶不受影响）。 */
    fun schedule(context: Context, letter: FutureLetterEntity) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmPendingIntent(context, letter.entryId, letter.scheduleRevision)
        manager.cancel(pending)
        if (!letter.notifyEnabled) return
        val at = letter.deliverAtMs
        if (at <= System.currentTimeMillis()) return
        try {
            when {
                canScheduleExact(context) ->
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                Build.VERSION.SDK_INT >= 23 ->
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else -> manager.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (error: SecurityException) {
            // 精确提醒被拒/ROM 限制：退化为普通调度，不阻断封存
            manager.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
        // 补偿：一次性后台任务，用于闹钟被杀后重试与对账（不作为分钟级时钟）
        WorkManager.getInstance(context).enqueueUniqueWork(
            "letter-due-${letter.entryId}",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LetterDueWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    fun cancel(context: Context, entryId: String, revision: Int = 0) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager?.cancel(alarmPendingIntent(context, entryId, revision))
        manager?.cancel(alarmPendingIntent(context, entryId, revision + 1))
        WorkManager.getInstance(context).cancelUniqueWork("letter-due-$entryId")
        LetterNotifier.cancel(context, entryId)
    }

    /** 闹钟到点：重新查库后再决定是否提醒（版本、删除、已读、开关都要复核）。 */
    fun onAlarm(context: Context, entryId: String?, revision: Int) {
        if (entryId == null) return
        scope.launch {
            runCatching {
                val database = ImprintDatabase.get(context)
                val mailDao = database.mailReviewDao()
                val letter = mailDao.letter(entryId) ?: return@runCatching
                if (letter.scheduleRevision != revision) return@runCatching // 旧版本任务
                if (letter.deliveryReadAtMs != null) return@runCatching
                val entry = database.dao().entry(entryId) ?: return@runCatching
                if (entry.deletedAtMs != null) return@runCatching
                LetterRepository(database, MediaFiles(context), ImprintRepository(database, MediaFiles(context), ImprintSecurity(database)))
                    .confirmDue(entryId)
                LetterNotifier.notifyDue(context)
            }
        }
    }

    /**
     * 全量对账：补查到期状态、重建未来调度、清理旧任务。
     * 前台到点、冷启动、开机、系统时间/时区变化、应用更新都调用它。
     *
     * [notify] = false 时只做状态与调度对账，**不发系统通知**——
     * 备份导入的历史到期信「仅应用内送达」，不能导入完就集中轰炸。
     */
    suspend fun reconcile(context: Context, notify: Boolean = true) {
        val database = ImprintDatabase.get(context)
        val files = MediaFiles(context)
        val security = ImprintSecurity(database)
        val core = ImprintRepository(database, files, security)
        val letters = LetterRepository(database, files, core)
        val mailDao = database.mailReviewDao()
        val now = System.currentTimeMillis()
        letters.refreshDeliveryState(now)
        // 未到期信：撤销旧任务后按当前版本重新注册
        mailDao.upcomingLetters(now).forEach { schedule(context, it) }
        // 只撤销「已读 / 已删除」的残留任务与通知。
        // 注意：**不能**把「已送达」当作撤通知的条件——送达时闹钟刚发出通知，
        // 紧接着的 WorkManager 补偿任务会跑 reconcile，会把通知立刻撤掉（用户表现为"没有通知"）。
        mailDao.allLetters().forEach { letter ->
            val entry = database.dao().entry(letter.entryId)
            val gone = entry == null || entry.deletedAtMs != null
            val read = letter.deliveryReadAtMs != null
            if (gone || read) {
                cancel(context, letter.entryId, letter.scheduleRevision)
            }
        }
        if (notify) LetterNotifier.notifyDue(context)
    }

    /** 后台补偿与周期维护用。 */
    class LetterDueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = runCatching {
            reconcile(applicationContext)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** 送达闹钟：只做轻量校验后交给调度对账，不在 Receiver 里做大媒体读取。 */
class LetterAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra("entryId") ?: return
        val revision = intent.getIntExtra("revision", -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LetterScheduler.onAlarm(context, entryId, revision)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 开机 / 系统时间与时区变化 / 应用更新：重建未来调度并补查到期状态。
 * 通知权限恢复后**不补发**历史被抑制的提醒，只为未来任务恢复能力。
 */
class LetterBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { LetterScheduler.reconcile(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
