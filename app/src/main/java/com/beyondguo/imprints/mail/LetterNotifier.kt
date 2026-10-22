package com.beyondguo.imprints.mail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.beyondguo.imprints.MainActivity
import com.beyondguo.imprints.R
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.LetterRepository
import com.beyondguo.imprints.data.LetterNotificationEventEntity
import com.beyondguo.imprints.data.MediaFiles
import com.beyondguo.imprints.security.ImprintSecurity

/**
 * 时光邮箱的通知（ML-06）。
 *
 * 隐私红线：通知**只显示通用文案**，不含自定义标题、正文、附件或地点；
 * 通知、日志与调度只携带信件 ID 和版本。锁屏采用私密展示（内容本身已是通用文案）。
 *
 * 幂等：同一 (entryId, scheduleRevision) 只提醒一次，稳定 notificationId 覆盖同一通知；
 * 清除通知不修改信件状态；前台正在阅读该信时不再发该信通知。
 */
object LetterNotifier {
    /**
     * 渠道 id 带版本：Android 渠道属性创建后**不可修改**。
     * v1（`mail_delivery`）被误设成静音（`setSound(null)`）导致「开了到时通知却没声音」，
     * 只能换新 id 重建，并删除旧渠道。
     */
    const val CHANNEL_ID = "mail_delivery_v2"
    private const val LEGACY_CHANNEL_ID = "mail_delivery"
    const val EXTRA_LETTER_ID = "com.beyondguo.imprints.extra.LETTER_ID"
    /** 聚合通知（多封一起补发）不对应单封信：带这个标记，点击后落地到时光邮箱列表 */
    const val EXTRA_OPEN_MAIL = "com.beyondguo.imprints.extra.OPEN_MAIL"
    private const val SUMMARY_ID = 4200
    private const val ID_BASE = 4300
    private const val TAG = "ImprintsMail"

    /** 前台正在阅读的信件 id：此时不再发该信通知。 */
    @Volatile
    var foregroundLetterId: String? = null

    /** 稳定 notificationId：同一封信的不同版本任务覆盖同一通知。 */
    fun notificationIdFor(entryId: String): Int = ID_BASE + (entryId.hashCode() and 0x0FFF)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 旧静音渠道已废弃：删掉，避免设置页出现一个永远不出声的「时光邮箱」
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 普通可听提醒：系统默认提示音 + 默认重要级（有声、不横幅、可清除）；
        // 不用全屏通知、闹钟式持续响铃或不可清除通知。最终声音/横幅仍受用户渠道设置影响。
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.mail_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.mail_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context, entryId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_LETTER_ID, entryId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            notificationIdFor(entryId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 对账并提醒：给所有「到期未读、开启到时通知、当前版本未提醒过」的信发通用提醒。
     * 多封一起补发时合并为一张汇总通知（「你有 N 封来自过去的信」）。
     */
    suspend fun notifyDue(context: Context) {
        val database = ImprintDatabase.get(context)
        val mailDao = database.mailReviewDao()
        val letters = mailDao.dueLetters(System.currentTimeMillis())
            .filter { it.notifyEnabled && it.deliveryReadAtMs == null && it.entryId != foregroundLetterId }
        val pending = letters.filter { letter ->
            val events = mailDao.notificationEvents(letter.entryId)
            events.none { it.scheduleRevision == letter.scheduleRevision && it.postedAtMs != null }
        }
        android.util.Log.i(TAG, "notifyDue due=${letters.size} pending=${pending.size}")
        if (pending.isEmpty()) return
        val canPostNow = canPost(context)
        android.util.Log.i(TAG, "notifyDue canPost=$canPostNow enabled=${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
        if (!canPostNow) {
            pending.forEach { record(mailDao, it.entryId, it.scheduleRevision, posted = false, suppressed = "NO_PERMISSION") }
            return
        }
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        try {
            if (pending.size == 1) {
                val letter = pending.first()
                manager.notify(
                    notificationIdFor(letter.entryId),
                    baseBuilder(context)
                        .setContentIntent(contentIntent(context, letter.entryId))
                        .build(),
                )
                android.util.Log.i(TAG, "notifyDue posted single id=${notificationIdFor(letter.entryId)}")
                record(mailDao, letter.entryId, letter.scheduleRevision, posted = true)
            } else {
                val tap = PendingIntent.getActivity(
                    context,
                    SUMMARY_ID,
                    Intent(context, MainActivity::class.java)
                        // 聚合通知没有单封信 ID，必须显式标记「打开邮箱」，
                        // 否则点进来 pendingLetterTarget 为空、停在首页（曾是这个 bug）
                        .putExtra(EXTRA_OPEN_MAIL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.notify(
                    SUMMARY_ID,
                    baseBuilder(context)
                        .setContentTitle(context.getString(R.string.mail_notify_summary_title, pending.size))
                        .setContentText(context.getString(R.string.mail_notify_summary_text))
                        .setContentIntent(tap)
                        .build(),
                )
                android.util.Log.i(TAG, "notifyDue posted summary count=${pending.size}")
                pending.forEach { record(mailDao, it.entryId, it.scheduleRevision, posted = true) }
            }
        } catch (error: SecurityException) {
            android.util.Log.e(TAG, "notifyDue security exception", error)
            pending.forEach { record(mailDao, it.entryId, it.scheduleRevision, posted = false, suppressed = "NO_PERMISSION") }
        } catch (error: Exception) {
            // 之前这里只捕获 SecurityException，其它异常被外层 runCatching 吞掉，表现为「静默无通知」
            android.util.Log.e(TAG, "notifyDue failed", error)
            pending.forEach { record(mailDao, it.entryId, it.scheduleRevision, posted = false, suppressed = "POST_FAILED") }
        }
    }

    /** 通用文案：不含标题、正文、附件、地点。 */
    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            // 单色 silhouette 图标；不要用 app 的 launcher 图标（自适应图标会被部分 ROM 丢弃通知）
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.mail_notify_title))
            .setContentText(context.getString(R.string.mail_notify_text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    private suspend fun record(
        mailDao: com.beyondguo.imprints.data.MailReviewDao,
        entryId: String,
        revision: Int,
        posted: Boolean,
        suppressed: String? = null,
    ) {
        val now = System.currentTimeMillis()
        mailDao.upsertNotificationEvent(
            LetterNotificationEventEntity(
                entryId = entryId,
                scheduleRevision = revision,
                notificationId = notificationIdFor(entryId),
                attemptState = if (posted) "POSTED" else "SUPPRESSED",
                lastAttemptAtMs = now,
                postedAtMs = if (posted) now else null,
                suppressedReason = suppressed,
            ),
        )
    }

    /** 信件已读/删除/改期后撤掉对应通知。 */
    fun cancel(context: Context, entryId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(entryId))
    }

    /** 冷启动/解锁后消费通知目标时的入口对象（不含任何内容数据）。 */
    data class LetterTarget(val entryId: String)
}
