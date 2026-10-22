package com.beyondguo.imprints.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.imprints.R
import com.beyondguo.imprints.data.LetterRepository
import com.beyondguo.imprints.data.SealedLetterMeta
import com.beyondguo.imprints.mail.LetterScheduler
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SEALED_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/**
 * 设置 →「管理封存信件」（ML-02 / V02-02）。
 *
 * 这是**唯一的主动管理入口**，刻意做得低频且信息最少：
 * - 列表只显示「封存于 / 送达于」，整行不可点，进不了信封或正文；
 * - 不显示标题、正文、缩略图、附件数、倒计时，也不显示已封存总数；
 * - 只能做两件事：改期（只写送达元数据）、删除（进 7 天回收站）。
 *
 * 规格见 `产品设计/2026-10-16_时光邮箱与回顾/02_时光邮箱产品规格.md` 第 2 节。
 */
@Composable
fun SealedLettersPage(
    repository: LetterRepository,
    nowMs: Long,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metas by repository.sealedMetas.collectAsState(initial = emptyList())
    // 已经过了约定时刻（但还没确认交付）的信不属于「封存」了，从管理页移出。
    // 不在这里自动拆信，也不做已读标记 —— 只是不再展示。
    val sealed = remember(metas, nowMs) {
        metas.filter { it.deliverAtMs > nowMs }
            .sortedWith(compareBy<SealedLetterMeta> { it.deliverAtMs }.thenBy { it.entryId })
    }
    var rescheduling by remember { mutableStateOf<SealedLetterMeta?>(null) }
    var deleting by remember { mutableStateOf<SealedLetterMeta?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { SettingsSectionLabel(stringResource(R.string.mail_manage_sealed)) }
        item {
            Text(
                stringResource(R.string.mail_manage_sealed_intro),
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                color = Quiet, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        if (sealed.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.mail_manage_sealed_empty),
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    color = Quiet, fontSize = 14.sp,
                )
            }
        }
        items(sealed, key = { it.entryId }) { meta ->
            SealedLetterRow(
                meta = meta,
                onReschedule = { rescheduling = meta },
                onDelete = { deleting = meta },
            )
        }
    }

    rescheduling?.let { meta ->
        val zone = runCatching { ZoneId.of(meta.deliverZoneId) }.getOrDefault(ZoneId.systemDefault())
        EventTimePickerSheet(
            initial = LocalDateTime.ofInstant(Instant.ofEpochMilli(meta.deliverAtMs), zone),
            title = stringResource(R.string.mail_manage_reschedule),
            subtitle = null,
            showBackToToday = false,
            showQuickJumps = true,
            onConfirm = { picked ->
                rescheduling = null
                scope.launch {
                    runCatching {
                        // 只改送达时间：不读也不改正文／附件，失败时原计划保持不变
                        repository.reschedule(meta.entryId, picked.format(SEALED_MINUTE_FORMAT), meta.deliverZoneId)
                    }.onSuccess {
                        // 修订号已递增：按数据库重新对账所有调度，旧版本任务自行作废
                        runCatching { LetterScheduler.reconcile(context) }
                    }.onFailure(onError)
                }
            },
            onDismiss = { rescheduling = null },
        )
    }

    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.mail_manage_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mail_manage_delete_message,
                        formatSealedMoment(meta.deliverAtMs),
                        stringResource(R.string.mail_sealed_at, formatSealedMoment(meta.sealedAtMs)),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val revision = meta.scheduleRevision
                    val id = meta.entryId
                    scope.launch {
                        runCatching { repository.moveToRecycle(id) }
                            .onSuccess {
                                // 数据库先落库；通知与任务取消失败可由对账补偿
                                runCatching { LetterScheduler.cancel(context, id, revision) }
                                deleting = null
                            }
                            .onFailure(onError)
                    }
                }) { Text(stringResource(R.string.mail_manage_delete), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * 管理页的日期行：只有两个时刻 + 两个动作，整行不可点。
 * 刻意不接收标题或正文，避免后续有人顺手把正文塞进这个入口。
 */
@Composable
private fun SealedLetterRow(
    meta: SealedLetterMeta,
    onReschedule: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinePanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.mail_sealed_at, formatSealedMoment(meta.sealedAtMs)),
                color = Quiet, fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.mail_deliver_at, formatSealedMoment(meta.deliverAtMs)),
                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReschedule) {
                    Text(stringResource(R.string.mail_manage_reschedule), color = Green, fontSize = 14.sp)
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.mail_manage_delete), color = Danger, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 管理页只到分钟：YYYY年MM月DD日 HH:mm（与信件其它位置一致）。 */
private fun formatSealedMoment(epochMs: Long): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return "%04d年%02d月%02d日 %02d:%02d".format(time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute)
}
