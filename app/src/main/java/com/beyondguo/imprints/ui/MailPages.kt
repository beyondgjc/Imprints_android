package com.beyondguo.imprints.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyondguo.imprints.R
import com.beyondguo.imprints.data.AudioChoice
import com.beyondguo.imprints.data.ContentDraftEntity
import com.beyondguo.imprints.data.LetterAttachment
import com.beyondguo.imprints.data.LetterDraft
import com.beyondguo.imprints.data.LetterListRow
import com.beyondguo.imprints.data.LetterRepository
import com.beyondguo.imprints.data.MediaChoice
import com.beyondguo.imprints.data.PreparedMedia
import com.beyondguo.imprints.data.VisualMediaEntity
import com.beyondguo.imprints.mail.LetterNotifier
import com.beyondguo.imprints.mail.LetterRules
import com.beyondguo.imprints.mail.LetterScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---------- 通用时间文案 ----------

private val MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

@Composable
private fun formatMinute(minute: String): String {
    val ldt = runCatching { LocalDateTime.parse(minute, MINUTE_FORMAT) }.getOrNull() ?: return minute
    return stringResource(R.string.format_datetime_ymd_hm, ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
}

@Composable
private fun formatDateOnly(minute: String): String {
    val ldt = runCatching { LocalDateTime.parse(minute, MINUTE_FORMAT) }.getOrNull() ?: return minute.take(10)
    // 展示用纯日期：不能复用选择器里的「已选：」文案，否则列表会显示「写于 已选：…」
    return stringResource(R.string.format_date_ymd, ldt.year, ldt.monthValue, ldt.dayOfMonth)
}

@Composable
private fun formatEpochDate(ms: Long): String {
    val date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return stringResource(R.string.format_date_ymd, date.year, date.monthValue, date.dayOfMonth)
}

/** 封面送达文案：临近一天显示「明天送达」，其余显示明确日期（日期是主信息）。 */
@Composable
private fun deliverCoverLabel(row: LetterListRow, nowMs: Long): String = when (LetterRules.coverLabelKind(row.toEntity(), nowMs)) {
    LetterRules.CoverLabel.DUE -> stringResource(R.string.mail_deliver_on, formatDateOnly(row.deliverLocalMinute))
    LetterRules.CoverLabel.TODAY -> stringResource(R.string.mail_deliver_today)
    LetterRules.CoverLabel.TOMORROW -> stringResource(R.string.mail_deliver_tomorrow)
    LetterRules.CoverLabel.DATE -> stringResource(R.string.mail_deliver_on, formatDateOnly(row.deliverLocalMinute))
}

/** 列表投影 → 状态判定用的实体壳（不读正文）。 */
internal fun LetterListRow.toEntity() =
    com.beyondguo.imprints.data.FutureLetterEntity(
        entryId = entryId, title = title, deliverAtMs = deliverAtMs,
        deliverLocalMinute = deliverLocalMinute, deliverZoneId = deliverZoneId,
        deliveredAtMs = deliveredAtMs, firstOpenedAtMs = firstOpenedAtMs,
        deliveryReadAtMs = deliveryReadAtMs, notifyEnabled = notifyEnabled, scheduleRevision = scheduleRevision,
    )

// ---------- 邮箱一级页 ----------

@Composable
fun MailHomePage(
    repository: LetterRepository,
    nowMs: Long,
    onOpenEnvelope: (String) -> Unit,
    onWrite: () -> Unit,
    onOpenDraft: (String) -> Unit,
    onDeleteDraft: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sealed by repository.sealedLetters.collectAsState(initial = emptyList())
    val drafts by repository.letterDrafts.collectAsState(initial = emptyList())
    var showUnreadOnly by remember { mutableStateOf(false) }
    // 待送达 / 已送达 两个分组：点标签切换，默认看「待送达」
    var showDeliveredTab by remember { mutableStateOf(false) }
    val delivered = sealed.filter { LetterRules.isDue(it.toEntity(), nowMs) }
        .sortedWith(compareByDescending<LetterListRow> { it.deliverAtMs }.thenByDescending { it.entryId })
    val waiting = sealed.filter { !LetterRules.isDue(it.toEntity(), nowMs) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.mail_title), Modifier.weight(1f), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onWrite, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.mail_write)) }
        }

        if (drafts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.mail_draft_badge, drafts.size), color = Quiet, fontSize = 13.sp)
            }
            drafts.forEach { draft ->
                DraftRow(draft = draft, onOpen = { onOpenDraft(draft.id) }, onDelete = { onDeleteDraft(draft.id) })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TagChip(
                text = stringResource(R.string.mail_tab_pending) + " " + waiting.size,
                selected = !showDeliveredTab,
                onClick = { showDeliveredTab = false },
            )
            TagChip(
                text = stringResource(R.string.mail_tab_delivered) + " " + delivered.size,
                selected = showDeliveredTab,
                onClick = { showDeliveredTab = true },
            )
            Spacer(Modifier.weight(1f))
            if (showDeliveredTab && delivered.any { it.deliveryReadAtMs == null }) {
                TagChip(
                    text = stringResource(R.string.mail_unread_only),
                    selected = showUnreadOnly,
                    onClick = { showUnreadOnly = !showUnreadOnly },
                )
            }
        }

        val visible = if (showDeliveredTab) {
            delivered.filter { !showUnreadOnly || it.deliveryReadAtMs == null }
        } else {
            waiting
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(if (showDeliveredTab) R.string.mail_empty_delivered else R.string.mail_empty_pending),
                            color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(if (showDeliveredTab) R.string.mail_empty_delivered_sub else R.string.mail_empty_pending_sub),
                            color = Quiet, fontSize = 14.sp,
                        )
                        if (!showDeliveredTab) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onWrite, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.mail_write)) }
                        }
                    }
                }
            }
            items(visible, key = { (if (showDeliveredTab) "d-" else "w-") + it.entryId }) { row ->
                LetterCard(row = row, nowMs = nowMs, onClick = { onOpenEnvelope(row.entryId) })
            }
        }
    }
}

@Composable
private fun DraftRow(draft: ContentDraftEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val payload = runCatching { org.json.JSONObject(draft.payload) }.getOrNull()
    val title = payload?.optString("title").orEmpty().ifBlank { stringResource(R.string.mail_letter_default_title) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.MailOutline, null, tint = Quiet, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(stringResource(R.string.mail_drafts) + " · " + formatEpochDate(draft.updatedAtMs), color = Quiet, fontSize = 12.sp)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Text("×", color = Quiet, fontSize = 20.sp) }
    }
}

@Composable
private fun LetterCard(row: LetterListRow, nowMs: Long, onClick: () -> Unit) {
    val due = LetterRules.isDue(row.toEntity(), nowMs)
    OutlinePanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LetterRules.displayTitle(row.title),
                    Modifier.weight(1f),
                    color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                if (due && row.deliveryReadAtMs == null) {
                    Text(
                        stringResource(R.string.mail_state_unread),
                        Modifier.background(PaleGreen, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                        color = Green, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            Text(stringResource(R.string.mail_written_on, formatEpochDate(row.createdAtMs)), color = Quiet, fontSize = 12.sp)
            Text(deliverCoverLabel(row, nowMs), color = if (due) Green else Quiet, fontSize = 13.sp)
        }
    }
}

// ---------- 信封详情 ----------

@Composable
fun LetterEnvelopePage(
    repository: LetterRepository,
    letterId: String,
    nowMs: Long,
    onOpenBody: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onRescheduled: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    var row by remember(letterId) { mutableStateOf<LetterListRow?>(null) }
    var entity by remember(letterId) { mutableStateOf<com.beyondguo.imprints.data.FutureLetterEntity?>(null) }
    var confirmPeek by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showReschedule by remember { mutableStateOf(false) }

    LaunchedEffect(letterId, nowMs) {
        row = repository.letterRow(letterId)
        entity = repository.letter(letterId)
    }

    val current = row
    val letter = entity
    if (current == null || letter == null) {
        Text(stringResource(R.string.state_loading), Modifier.padding(20.dp))
        return
    }
    val due = LetterRules.isDue(letter, nowMs)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(LetterRules.displayTitle(letter.title), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.envelope_written, formatEpochDate(current.createdAtMs)), color = Quiet, fontSize = 13.sp)
            Text(stringResource(R.string.envelope_deliver, formatMinute(letter.deliverLocalMinute)), color = Quiet, fontSize = 13.sp)
            letter.deliveredAtMs?.let { Text(stringResource(R.string.envelope_delivered_at, formatEpochDate(it)), color = Quiet, fontSize = 13.sp) }
            letter.firstOpenedAtMs?.let { Text(stringResource(R.string.envelope_first_opened, formatEpochDate(it)), color = Quiet, fontSize = 13.sp) }
            letter.deliveryReadAtMs?.let { Text(stringResource(R.string.envelope_first_read, formatEpochDate(it)), color = Quiet, fontSize = 13.sp) }
        }

        if (!due) Text(stringResource(R.string.envelope_reseal_edit_hint), color = Quiet, fontSize = 12.sp)

        Button(
            onClick = {
                if (due) {
                    scope.launch { runCatching { repository.markEnvelopeOpened(letterId) } }
                    onOpenBody()
                } else confirmPeek = true
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (due) stringResource(R.string.envelope_open) else stringResource(R.string.envelope_peek)) }

        if (!due) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton2(stringResource(R.string.envelope_reschedule)) { showReschedule = true }
                OutlinedButton2(stringResource(R.string.action_edit)) {
                    scope.launch {
                        runCatching { repository.markEnvelopeOpened(letterId) }
                        onEdit()
                    }
                }
                OutlinedButton2(stringResource(R.string.envelope_delete), danger = true) { confirmDelete = true }
            }
        }
    }

    if (confirmPeek) AlertDialog(
        onDismissRequest = { confirmPeek = false },
        title = { Text(stringResource(R.string.envelope_peek_confirm_title)) },
        text = { Text(stringResource(R.string.envelope_peek_confirm_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirmPeek = false
                scope.launch { runCatching { repository.markEnvelopeOpened(letterId) } }
                onOpenBody()
            }) { Text(stringResource(R.string.envelope_peek)) }
        },
        dismissButton = { TextButton(onClick = { confirmPeek = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (showReschedule) {
        EventTimePickerSheet(
            initial = runCatching { LocalDateTime.parse(letter.deliverLocalMinute, MINUTE_FORMAT) }.getOrDefault(LocalDateTime.now().plusDays(1)),
            title = stringResource(R.string.envelope_reschedule),
            subtitle = stringResource(R.string.letter_deliver_pick),
            showBackToToday = false,
            onConfirm = { picked ->
                showReschedule = false
                scope.launch {
                    runCatching {
                        repository.reschedule(letterId, picked.format(MINUTE_FORMAT), ZoneId.systemDefault().id)
                    }.onSuccess {
                        repository.letter(letterId)?.let { LetterScheduler.schedule(context, it) }
                        onRescheduled()
                    }.onFailure(report)
                }
            },
            onDismiss = { showReschedule = false },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.envelope_delete)) },
        text = { Text(stringResource(R.string.letter_delete_message)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    runCatching {
                        repository.moveToRecycle(letterId)
                        LetterScheduler.cancel(context, letterId, letter.scheduleRevision)
                    }.onSuccess { onDeleted() }.onFailure(report)
                }
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun OutlinedButton2(text: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
        Text(text, color = if (danger) Danger else Green, fontSize = 15.sp)
    }
}

// ---------- 拆信正文页 ----------

@Composable
fun LetterBodyPage(
    repository: LetterRepository,
    letterId: String,
    nowMs: Long,
    onBack: () -> Unit,
    onAddToReview: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detail by remember(letterId) { mutableStateOf<com.beyondguo.imprints.data.LetterDetail?>(null) }
    var loadError by remember(letterId) { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(letterId, nowMs) {
        val loaded = runCatching { repository.letterDetail(letterId) }
        val value = loaded.getOrNull()
        detail = value
        loadError = loaded.exceptionOrNull()
        if (value != null) {
            // 正文成功显示才算到期已读；加载失败不标已读
            LetterNotifier.foregroundLetterId = letterId
            runCatching {
                repository.markEnvelopeOpened(letterId)
                if (LetterRules.isDue(value.letter, System.currentTimeMillis())) {
                    repository.markDeliveryRead(letterId)
                    LetterNotifier.cancel(context, letterId)
                }
            }
        }
    }
    DisposableEffect(letterId) {
        onDispose { LetterNotifier.foregroundLetterId = null }
    }

    val current = detail
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(loadError?.message ?: stringResource(R.string.state_loading), color = Ink, fontSize = 15.sp)
            Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
    ) {
        item {
            Text(LetterRules.displayTitle(current.letter.title), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.envelope_written, formatEpochDate(current.entry.createdAtMs)), color = Quiet, fontSize = 13.sp)
                Text(stringResource(R.string.envelope_deliver, formatMinute(current.letter.deliverLocalMinute)), color = Quiet, fontSize = 13.sp)
                current.letter.deliveryReadAtMs?.let {
                    Text(stringResource(R.string.envelope_first_read, formatEpochDate(it)), color = Quiet, fontSize = 13.sp)
                }
            }
        }
        item {
            Text(current.entry.body, color = Ink, fontSize = 17.sp, lineHeight = 27.sp)
        }
        if (current.visuals.isNotEmpty()) item {
            MomentsMediaGrid(
                current.visuals.map { v ->
                    MediaCell(
                        file = repository.files.resolve(v.relativePath),
                        kind = v.kind,
                        motionVideo = v.motionVideoPath?.let { repository.files.resolve(it) },
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }
        current.audio?.let { audio ->
            item { InlineAudio(repository.files.resolve(audio.relativePath)) }
        }
        if (current.letter.deliveryReadAtMs != null) item {
            Button(
                onClick = onAddToReview,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.letter_add_to_review)) }
        }
    }
}

@Composable
private fun isoMinuteOfEntry(createdAtMs: Long): String =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(createdAtMs), ZoneId.systemDefault()).format(MINUTE_FORMAT)

// ---------- 写信编辑器 ----------

@Composable
fun LetterEditorPage(
    repository: LetterRepository,
    draftId: String?,
    letterId: String?,
    prefillBody: String = "",
    onSealed: (String) -> Unit,
    onCancel: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionOwnerId = remember(draftId, letterId) { draftId ?: UUID.randomUUID().toString() }

    var title by remember(draftId, letterId) { mutableStateOf("") }
    var body by remember(draftId, letterId) { mutableStateOf(prefillBody) }
    val mediaChoices: SnapshotStateList<MediaChoice> = remember(draftId, letterId) { mutableStateListOf() }
    var audio by remember(draftId, letterId) { mutableStateOf<LetterAttachment?>(null) }
    var deliverMinute by remember(draftId, letterId) { mutableStateOf("") }
    var notifyEnabled by remember(draftId, letterId) { mutableStateOf(true) }
    var loading by remember(draftId, letterId) { mutableStateOf(draftId != null || letterId != null) }
    var saving by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showSealConfirm by remember { mutableStateOf(false) }
    var showDeliverPicker by remember { mutableStateOf(false) }
    var pendingSeal by remember { mutableStateOf(false) }
    var notifyPermissionOk by remember { mutableStateOf(true) }

    fun toChoice(a: LetterAttachment): MediaChoice = MediaChoice.Existing(
        VisualMediaEntity(
            id = a.path, entryId = "", kind = a.kind, ordinal = mediaChoices.size, relativePath = a.path,
            mime = a.mime, byteLength = a.byteLength, sha256 = a.sha256, createdAtMs = 0,
            motionVideoPath = a.motionPath, motionVideoSha256 = a.motionSha256, motionVideoByteLength = a.motionByteLength,
        ),
    )

    fun attachmentOf(choice: MediaChoice): LetterAttachment = when (choice) {
        is MediaChoice.Existing -> {
            val v = choice.value
            LetterAttachment(v.relativePath, v.kind, v.mime, v.byteLength, v.sha256, v.motionVideoPath, v.motionVideoSha256, v.motionVideoByteLength)
        }
        is MediaChoice.New -> LetterAttachment(
            choice.value.id, choice.value.kind, choice.value.mime, choice.value.byteLength, choice.value.sha256,
            choice.value.motionVideoId, choice.value.motionVideoSha256, choice.value.motionVideoByteLength,
        )
    }

    fun currentDraft() = LetterDraft(
        draftId = if (letterId == null) sessionOwnerId else null,
        id = letterId,
        title = title,
        body = body,
        visuals = mediaChoices.map(::attachmentOf),
        audio = audio,
        deliverLocalMinute = deliverMinute,
        deliverZoneId = ZoneId.systemDefault().id,
        notifyEnabled = notifyEnabled,
    )

    LaunchedEffect(draftId, letterId) {
        if (draftId != null) {
            val draft = repository.draft(draftId)
            if (draft != null) {
                title = draft.title
                body = draft.body
                deliverMinute = draft.deliverLocalMinute
                notifyEnabled = draft.notifyEnabled
                mediaChoices.clear()
                mediaChoices.addAll(draft.visuals.map(::toChoice))
                audio = draft.audio
            }
        } else if (letterId != null) {
            val detail = repository.letterDetail(letterId)
            if (detail != null) {
                title = detail.letter.title
                body = detail.entry.body
                deliverMinute = detail.letter.deliverLocalMinute
                notifyEnabled = detail.letter.notifyEnabled
                mediaChoices.clear()
                mediaChoices.addAll(detail.visuals.map { v ->
                    toChoice(LetterAttachment(v.relativePath, v.kind, v.mime, v.byteLength, v.sha256, v.motionVideoPath, v.motionVideoSha256, v.motionVideoByteLength))
                })
                audio = detail.audio?.let { LetterAttachment(it.relativePath, "AUDIO", it.mime, it.byteLength, it.sha256) }
            }
        }
        loading = false
    }

    fun prepareUris(uris: List<Uri>) {
        scope.launch {
            uris.forEach { uri ->
                runCatching { repository.files.prepare(uri) }
                    .onSuccess { prepared ->
                        if (mediaChoices.size < LetterRules.MAX_VISUALS) {
                            runCatching { repository.commitAttachment(prepared, sessionOwnerId) }
                                .onSuccess { a -> mediaChoices.add(toChoice(a)) }
                                .onFailure { error -> repository.files.discard(prepared); report(error) }
                        } else {
                            repository.files.discard(prepared)
                            report(IllegalStateException("媒体最多 ${LetterRules.MAX_VISUALS} 个"))
                        }
                    }.onFailure(report)
            }
        }
    }

    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(LetterRules.MAX_VISUALS)) {
        prepareUris(it)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepareUris(listOf(uri))
    }
    fun prepareAudioUri(uri: Uri) {
        scope.launch {
            runCatching { repository.files.prepare(uri, audio = true) }.onSuccess { prepared ->
                runCatching { repository.commitAttachment(prepared, sessionOwnerId) }
                    .onSuccess { a ->
                        audio?.let { old -> scope.launch { repository.releaseAttachment(sessionOwnerId, old) } }
                        audio = a
                    }
                    .onFailure { error -> repository.files.discard(prepared); report(error) }
            }.onFailure(report)
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareAudioUri(uri)
    }
    // 与印迹编辑器一致：文件 / 系统录音机（不自建录音 UI）
    var showAudioSourceSheet by remember { mutableStateOf(false) }
    val errPickRecordedAudio = stringResource(R.string.err_pick_recorded_audio)
    val errNoRecorder = stringResource(R.string.err_no_recorder)
    val systemRecorderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        when {
            uri != null -> prepareAudioUri(uri)
            // 录音机多不回传 URI：引导到文件选择器挑刚录的那条
            else -> {
                android.widget.Toast.makeText(context, errPickRecordedAudio, android.widget.Toast.LENGTH_SHORT).show()
                audioPicker.launch(arrayOf("audio/*"))
            }
        }
    }

    fun launchSystemRecorder() {
        // 与 MediaStore.Audio.Media.RECORD_SOUND_ACTION 同值；字符串避免依赖该常量
        val recordIntent = Intent("android.provider.MediaStore.RECORD_SOUND")
        if (recordIntent.resolveActivity(context.packageManager) != null) {
            systemRecorderLauncher.launch(recordIntent)
        } else {
            android.widget.Toast.makeText(context, errNoRecorder, android.widget.Toast.LENGTH_SHORT).show()
            audioPicker.launch(arrayOf("audio/*"))
        }
    }
    val notifyPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifyPermissionOk = granted
            if (pendingSeal) {
                pendingSeal = false
                // 无论授权与否都保存信件：通知不是保存的前提
                saving = true
                scope.launch {
                    runCatching {
                        val id = repository.seal(currentDraft())
                        repository.letter(id)?.let { LetterScheduler.schedule(context, it) }
                        if (!granted) report(IllegalStateException(context.getString(R.string.letter_notify_permission_off)))
                        id
                    }.onSuccess(onSealed).onFailure(report)
                    saving = false
                }
            }
        }

    fun seal() {
        val draft = currentDraft()
        if (!LetterRules.canSeal(draft.body, 0)) {
            report(IllegalStateException(context.getString(R.string.letter_body_required)))
            return
        }
        if (draft.deliverLocalMinute.isBlank() && letterId == null) {
            report(IllegalStateException(context.getString(R.string.letter_deliver_required)))
            return
        }
        if (letterId == null) {
            val resolution = runCatching { LetterRules.resolveDeliver(draft.deliverLocalMinute, draft.deliverZoneId) }
            resolution.exceptionOrNull()?.let { report(it); return }
            if (resolution.getOrThrow().instantMs <= System.currentTimeMillis()) {
                report(IllegalStateException(context.getString(R.string.letter_deliver_future)))
                return
            }
        }
        showSealConfirm = true
    }

    fun doSeal() {
        showSealConfirm = false
        val needPermission = notifyEnabled && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingSeal = true
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        saving = true
        scope.launch {
            runCatching {
                val id = repository.seal(currentDraft())
                repository.letter(id)?.let { LetterScheduler.schedule(context, it) }
                id
            }.onSuccess(onSealed).onFailure(report)
            saving = false
        }
    }

    fun saveDraftAndExit() {
        scope.launch {
            runCatching { repository.saveDraft(sessionOwnerId, currentDraft()) }
                .onSuccess { report(IllegalStateException(context.getString(R.string.letter_draft_saved))); onCancel() }
                .onFailure(report)
        }
    }

    fun requestExit() {
        val initialEmpty = title.isBlank() && body.isBlank() && mediaChoices.isEmpty() && audio == null
        if (initialEmpty) {
            scope.launch { repository.releaseSession(sessionOwnerId) }
            onCancel()
        } else showExitDialog = true
    }
    BackHandler { requestExit() }

    if (loading) {
        Text(stringResource(R.string.state_loading), Modifier.padding(20.dp))
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = ::requestExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(stringResource(R.string.action_cancel), color = Ink, fontSize = 16.sp)
            }
            Text(
                if (letterId == null) stringResource(R.string.letter_new_title) else stringResource(R.string.letter_edit_title),
                Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = ::seal,
                enabled = !saving && LetterRules.canSeal(body, 0),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { Text(if (saving) stringResource(R.string.state_saving) else stringResource(R.string.letter_seal)) }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.letter_title_hint), color = Quiet) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green),
                )
            }
            item {
                TextField(
                    body, { body = it },
                    placeholder = { Text(stringResource(R.string.letter_body_hint), color = Quiet) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp, color = Ink),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            item {
                EditorMediaGrid(
                    visuals = mediaChoices,
                    repository = repository.core,
                    onAdd = {
                        when (LetterRules.MAX_VISUALS - mediaChoices.size) {
                            0 -> report(IllegalStateException("媒体最多 ${LetterRules.MAX_VISUALS} 个"))
                            1 -> singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            else -> multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                    },
                    onRemove = { index ->
                        val removed = mediaChoices.removeAt(index)
                        val attachment = attachmentOf(removed)
                        scope.launch { repository.releaseAttachment(sessionOwnerId, attachment) }
                    },
                    report = report,
                )
            }
            item {
                when (val selected = audio) {
                    null -> EditorMetaRow(
                        leadingContent = { Text("♪", color = Quiet, fontSize = 18.sp) },
                        title = stringResource(R.string.editor_audio_title),
                        subtitle = null,
                        titleColor = Quiet,
                        onClick = { showAudioSourceSheet = true },
                        trailing = EditorMetaTrailing.Chevron,
                    )
                    else -> EditorAudioSelectedRow(
                        file = repository.files.resolve(selected.path),
                        displayName = friendlyAudioName(selected.path),
                        onRemove = {
                            val removed = audio
                            audio = null
                            if (removed != null) scope.launch { repository.releaseAttachment(sessionOwnerId, removed) }
                        },
                        onReplace = { showAudioSourceSheet = true },
                    )
                }
            }
            item {
                EditorMetaRow(
                    leadingContent = { Icon(Icons.Default.DateRange, null, tint = Quiet, modifier = Modifier.size(20.dp)) },
                    title = stringResource(R.string.letter_deliver_row),
                    subtitle = if (deliverMinute.isBlank()) null else formatMinute(deliverMinute),
                    titleColor = if (deliverMinute.isBlank()) Quiet else Ink,
                    onClick = { showDeliverPicker = true },
                    trailing = EditorMetaTrailing.Chevron,
                )
            }
            // 快捷时间（ML-01/ML-16）：一周后 / 一个月后 / 一年后取上午 9:00，目标日不存在取月末；可再改
            item {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TagChip(
                        text = stringResource(R.string.letter_quick_week),
                        selected = false,
                        onClick = { deliverMinute = LetterRules.quickDeliverMinuteWeeks(LocalDate.now(), 1) },
                    )
                    TagChip(
                        text = stringResource(R.string.letter_quick_month),
                        selected = false,
                        onClick = { deliverMinute = LetterRules.quickDeliverMinute(LocalDate.now(), 1) },
                    )
                    TagChip(
                        text = stringResource(R.string.letter_quick_year),
                        selected = false,
                        onClick = { deliverMinute = LetterRules.quickDeliverMinute(LocalDate.now(), 12) },
                    )
                    TagChip(
                        text = stringResource(R.string.letter_quick_custom),
                        selected = false,
                        onClick = { showDeliverPicker = true },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.letter_notify_row), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (notifyEnabled) stringResource(R.string.letter_notify_on) else stringResource(R.string.letter_notify_off),
                            color = Quiet, fontSize = 13.sp,
                        )
                        if (notifyEnabled && !LetterScheduler.canScheduleExact(context)) {
                            Text(stringResource(R.string.letter_exact_off), color = Quiet, fontSize = 12.sp)
                        }
                        // 进程被清理后还能不能提醒，取决于电池优化白名单（MIUI 未豁免会丢弃精确闹钟）
                        if (notifyEnabled && !com.beyondguo.imprints.mail.LetterBackgroundPolicy.isExempt(context)) {
                            Text(
                                stringResource(R.string.letter_background_hint),
                                Modifier.clickable {
                                    com.beyondguo.imprints.mail.LetterBackgroundPolicy.requestExemption(context)
                                },
                                color = Green, fontSize = 12.sp,
                            )
                        }
                    }
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { notifyEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White),
                    )
                }
            }
        }
    }

    if (showDeliverPicker) {
        EventTimePickerSheet(
            // 未选过时定位到「现在」，不是一个月后：用户自己决定往后推多久
            initial = runCatching { LocalDateTime.parse(deliverMinute, MINUTE_FORMAT) }.getOrDefault(LocalDateTime.now()),
            title = stringResource(R.string.letter_deliver_pick),
            subtitle = null,
            showBackToToday = false,
            showQuickJumps = true,
            onConfirm = { picked ->
                deliverMinute = picked.format(MINUTE_FORMAT)
                showDeliverPicker = false
            },
            onDismiss = { showDeliverPicker = false },
        )
    }

    // 与印迹编辑器一致：添加声音可选「文件 / 系统录音机」
    if (showAudioSourceSheet) AudioSourceSheet(
        onPickFile = {
            showAudioSourceSheet = false
            audioPicker.launch(arrayOf("audio/*"))
        },
        onRecord = {
            showAudioSourceSheet = false
            launchSystemRecorder()
        },
        onRemove = null,
        onDismiss = { showAudioSourceSheet = false },
    )

    if (showSealConfirm) AlertDialog(
        onDismissRequest = { showSealConfirm = false },
        title = { Text(stringResource(R.string.letter_seal_confirm_title)) },
        text = { Text(stringResource(R.string.letter_seal_confirm_message, formatMinute(deliverMinute))) },
        confirmButton = { TextButton(onClick = ::doSeal) { Text(stringResource(R.string.letter_seal)) } },
        dismissButton = { TextButton(onClick = { showSealConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (showExitDialog) AlertDialog(
        onDismissRequest = { showExitDialog = false },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_message)) },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    showExitDialog = false
                    saveDraftAndExit()
                }) { Text(stringResource(R.string.letter_draft_save)) }
                TextButton(onClick = {
                    showExitDialog = false
                    scope.launch { repository.releaseSession(sessionOwnerId) }
                    onCancel()
                }) { Text(stringResource(R.string.letter_draft_discard), color = Danger) }
            }
        },
        dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.editor_keep_editing)) } },
    )
}
