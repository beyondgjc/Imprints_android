package com.beyondguo.imprints.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyondguo.imprints.data.AudioChoice
import com.beyondguo.imprints.data.EntryDraft
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.MediaChoice
import com.beyondguo.imprints.data.PlaceEntity
import com.beyondguo.imprints.data.TagEntity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource

private data class EditorSnapshot(
    val body: String, val feeling: String, val eventTime: String, val placeName: String,
    val place: PlaceEntity?, val visuals: List<String>, val audio: String?,
    val tagIds: Set<String> = emptySet(),
)

@Composable
fun EditorPage(
    repository: ImprintRepository, id: String?, epoch: Int,
    onSaved: (String) -> Unit, onCancel: () -> Unit, onUnlock: () -> Unit,
    onBiometric: (() -> Unit)? = null,
    report: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var activePicker by remember { mutableStateOf<Dialog?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { activePicker?.dismiss(); activePicker = null }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); activePicker?.dismiss() }
    }
    var body by remember(id) { mutableStateOf("") }
    var feeling by remember(id) { mutableStateOf("") }
    var eventTime by remember(id) { mutableStateOf(EntryDraft().eventLocalMinute) }
    var placeName by remember(id) { mutableStateOf("") }
    var selectedPlace by remember(id) { mutableStateOf<PlaceEntity?>(null) }
    var showLocationPicker by remember(id) { mutableStateOf(false) }
    val visuals = remember(id) { mutableStateListOf<MediaChoice>() }
    var audio by remember(id) { mutableStateOf<AudioChoice?>(null) }
    var loading by remember(id) { mutableStateOf(id != null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var blocked by remember(id) { mutableStateOf(false) }
    var feelingLocked by remember(id) { mutableStateOf(false) }
    var feelingBioEnabled by remember(id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var extraUris by remember { mutableStateOf<List<Uri>?>(null) }
    var initial by remember(id) { mutableStateOf<EditorSnapshot?>(null) }
    var exitRequested by remember(id) { mutableStateOf(false) }
    var showEventTimePicker by remember(id) { mutableStateOf(false) }
    // 标签：availableTags 订阅标签表（预置 + 自定义），selectedTagIds 是本条的挂载集合（0~N）
    var selectedTagIds by remember(id) { mutableStateOf(emptySet<String>()) }
    val availableTags by repository.selectableTags.collectAsState(initial = emptyList())
    var showTagPicker by remember(id) { mutableStateOf(false) }

    // 这些文案在 LaunchedEffect / Toast 等非 Composable 回调里被引用，
    // 那里不能调 stringResource，所以在入口先取成局部 val 闭包捕获。
    val errEntryMissing = stringResource(R.string.err_entry_missing)
    val errTooManyMedia = stringResource(R.string.err_too_many_media)
    val errTooManyMediaPick = stringResource(R.string.err_too_many_media_pick)
    val errPickRecordedAudio = stringResource(R.string.err_pick_recorded_audio)
    val errNoRecorder = stringResource(R.string.err_no_recorder)

    fun snapshot() = EditorSnapshot(body, feeling, eventTime, placeName, selectedPlace,
        visuals.map { when (it) { is MediaChoice.Existing -> it.value.id; is MediaChoice.New -> it.value.id } },
        when (val selected = audio) { is AudioChoice.Existing -> selected.value.id; is AudioChoice.New -> selected.value.id; null -> null },
        selectedTagIds)
    fun discardPending() {
        visuals.forEach { if (it is MediaChoice.New) repository.files.discard(it.value) }
        (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
    }
    fun requestExit() {
        if (initial != null && initial != snapshot()) exitRequested = true
        else { discardPending(); onCancel() }
    }
    BackHandler(enabled = !showLocationPicker) { requestExit() }

    LaunchedEffect(id, epoch) {
        val sec = repository.security.state()
        feelingBioEnabled = sec.feelingBiometricEnvelope != null
        if (id == null) {
            feelingLocked = sec.feelingMode == "ON" && !repository.security.isFeelingUnlocked()
            if (initial == null) initial = snapshot()
        } else {
            val detail = repository.detail(id)
            if (detail == null) { loading = false; report(IllegalStateException(errEntryMissing)); return@LaunchedEffect }
            if (!loaded) {
                body = detail.entry.body
                eventTime = detail.entry.eventLocalMinute
                placeName = detail.place?.name.orEmpty()
                selectedPlace = detail.place
                visuals.clear()
                visuals.addAll(detail.visuals.map(MediaChoice::Existing))
                audio = detail.audio?.let(AudioChoice::Existing)
                // 标签不参与“感受解锁”分支，用 loaded 单独把住，避免锁屏返回后丢选择
                if (!loaded) selectedTagIds = detail.tagIds.toSet()
            }
            val decoded = runCatching { repository.security.decodeFeeling(detail.feeling) }
            val wasBlocked = blocked
            if (!loaded || blocked) feeling = decoded.getOrNull().orEmpty()
            blocked = decoded.isFailure
            feelingLocked = sec.feelingMode == "ON" && !repository.security.isFeelingUnlocked()
            if (!loaded || wasBlocked && decoded.isSuccess) initial = snapshot()
            loaded = true
        }
        loading = false
    }

    fun prepareUris(uris: List<Uri>) {
        scope.launch {
            uris.forEach { uri ->
                runCatching { repository.files.prepare(uri) }
                    .onSuccess { prepared ->
                        if (visuals.size < 9) visuals.add(MediaChoice.New(prepared))
                        else { repository.files.discard(prepared); report(IllegalStateException(errTooManyMedia)) }
                    }.onFailure(report)
            }
        }
    }

    fun receivedUris(uris: List<Uri>) {
        val remaining = 9 - visuals.size
        if (uris.size > remaining) extraUris = uris
        else prepareUris(uris)
    }

    fun prepareAudio(uri: Uri) {
        scope.launch {
            runCatching { repository.files.prepare(uri, audio = true) }.onSuccess { prepared ->
                (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                audio = AudioChoice.New(prepared)
            }.onFailure(report)
        }
    }

    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        receivedUris(it)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) receivedUris(listOf(uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareAudio(uri)
    }
    var showAudioSourceSheet by remember { mutableStateOf(false) }
    /** 录音机多不回传 URI，返回后引导到文件选择器挑刚录的那条 */
    var pendingRecorderReturn by remember { mutableStateOf(false) }
    val systemRecorderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        pendingRecorderReturn = false
        when {
            uri != null -> prepareAudio(uri)
            // 无 URI：打开音频文件选择器，用户点刚保存的录音
            else -> {
                Toast.makeText(context, errPickRecordedAudio, Toast.LENGTH_SHORT).show()
                audioPicker.launch(arrayOf("audio/*"))
            }
        }
    }

    fun launchSystemRecorder() {
        // 与 MediaStore.Audio.Media.RECORD_SOUND_ACTION 同值；字符串避免依赖该常量
        val recordIntent = Intent("android.provider.MediaStore.RECORD_SOUND")
        if (recordIntent.resolveActivity(context.packageManager) != null) {
            pendingRecorderReturn = true
            systemRecorderLauncher.launch(recordIntent)
        } else {
            Toast.makeText(context, errNoRecorder, Toast.LENGTH_SHORT).show()
            audioPicker.launch(arrayOf("audio/*"))
        }
    }

    if (loading) { Text(stringResource(R.string.state_loading), Modifier.padding(20.dp)); return }
    if (blocked) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.editor_unlock_feeling_first))
            Button(onClick = onUnlock) { Text(stringResource(R.string.editor_input_feeling_pin)) }
            TextButton(onClick = ::requestExit) { Text(stringResource(R.string.action_cancel)) }
        }
        if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text(stringResource(R.string.editor_keep_editing)) } })
        return
    }

    fun saveEntry() {
        saving = true
        scope.launch {
            val draft = EntryDraft(id = id, body = body, eventLocalMinute = eventTime,
                visuals = visuals.toList(), audio = audio, feeling = feeling,
                tagIds = selectedTagIds.toList(),
                place = selectedPlace ?: placeName.trim().takeIf(String::isNotEmpty)?.let {
                    PlaceEntity(UUID.randomUUID().toString(), it, "", null, null, null, "MANUAL", null)
                })
            runCatching { repository.save(draft) }.onSuccess(onSaved).onFailure(report)
            saving = false
        }
    }

    if (showLocationPicker) {
        LocationPickerPage(
            selected = selectedPlace,
            onSelect = { place ->
                selectedPlace = place
                placeName = place?.name.orEmpty()
                showLocationPicker = false
            },
            onBack = { showLocationPicker = false },
            report = report,
        )
        if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text(stringResource(R.string.editor_keep_editing)) } })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = ::requestExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(stringResource(R.string.action_cancel), color = Ink, fontSize = 16.sp)
            }
            Text(if (id == null) stringResource(R.string.editor_new_title) else stringResource(R.string.editor_edit_title), Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = ::saveEntry, enabled = !saving && EntryRules.canSave(body, visuals.size,
                feelingLocked && feeling.isNotBlank()), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(if (saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save))
            }
        }
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        // 正文 → 图片/媒体 → 音频 → 位置 → 标签 → 事件时间 → 感受
        item { TextField(body, { body = it }, placeholder = { Text(stringResource(R.string.editor_body_hint), color = Quiet) },
            modifier = Modifier.fillMaxWidth(), minLines = 5, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp, color = Ink),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent)) }
        // 媒体：无分区标题、无中间分隔线，直接网格 +「图片/视频」入口
        item {
            EditorMediaGrid(
                visuals = visuals,
                repository = repository,
                onAdd = {
                    when (EntryRules.MAX_VISUALS - visuals.size) {
                        0 -> report(IllegalStateException(errTooManyMediaPick))
                        1 -> singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        else -> multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                },
                onRemove = { index ->
                    val removed = visuals.removeAt(index)
                    if (removed is MediaChoice.New) repository.files.discard(removed.value)
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
                is AudioChoice.Existing -> {
                    val file = repository.files.resolve(selected.value.relativePath)
                    EditorAudioSelectedRow(
                        file = file,
                        displayName = friendlyAudioName(file.nameWithoutExtension),
                        onRemove = { audio = null },
                        onReplace = { showAudioSourceSheet = true },
                    )
                }
                is AudioChoice.New -> EditorAudioSelectedRow(
                    file = selected.value.pendingFile,
                    displayName = friendlyAudioName(selected.value.pendingFile.nameWithoutExtension),
                    onRemove = {
                        repository.files.discard(selected.value)
                        audio = null
                    },
                    onReplace = { showAudioSourceSheet = true },
                )
            }
        }
        item {
            val placeTitle = selectedPlace?.name?.takeIf { it.isNotBlank() }
                ?: placeName.trim().takeIf { it.isNotBlank() }
            val placeSelected = placeTitle != null
            if (placeSelected) {
                EditorMetaRow(
                    leadingContent = {
                        Icon(Icons.Default.LocationOn, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    },
                    leadingTint = Green,
                    title = placeTitle,
                    subtitle = selectedPlace?.address?.takeIf { it.isNotBlank() } ?: stringResource(R.string.editor_tap_change_place),
                    titleColor = Green,
                    onClick = { showLocationPicker = true },
                    trailing = EditorMetaTrailing.Clear,
                    onClear = {
                        selectedPlace = null
                        placeName = ""
                    },
                )
            } else {
                EditorMetaRow(
                    leadingContent = {
                        Icon(Icons.Default.LocationOn, null, tint = Quiet, modifier = Modifier.size(20.dp))
                    },
                    title = stringResource(R.string.editor_place_label),
                    subtitle = null,
                    titleColor = Quiet,
                    onClick = { showLocationPicker = true },
                    trailing = EditorMetaTrailing.Chevron,
                )
            }
        }
        // 添加标签：位于位置下方，与声音/位置/事件时间同一套条目行，点按进入「选择标签」面板
        item {
            EditorTagRow(
                tags = availableTags,
                selectedIds = selectedTagIds,
                onClick = { showTagPicker = true },
                onClear = { selectedTagIds = emptySet() },
            )
        }
        // 事件时间：与音乐/位置/标签同一套 Meta 行，位于标签下方
        item {
            EditorMetaRow(
                leadingContent = {
                    Icon(Icons.Default.DateRange, null, tint = Quiet, modifier = Modifier.size(20.dp))
                },
                title = stringResource(R.string.editor_event_time_label),
                subtitle = stringResource(R.string.editor_event_time_value, eventTime.take(10), eventTime.takeLast(5)),
                titleColor = Ink,
                onClick = { showEventTimePicker = true },
                trailing = EditorMetaTrailing.Chevron,
            )
        }
        item {
            TreasureFeelingSection(
                feeling = feeling,
                onFeelingChange = { feeling = it },
                feelingLocked = feelingLocked,
                onUnlock = onUnlock,
                onBiometric = onBiometric,
                feelingBiometricEnabled = feelingBioEnabled,
            )
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.editor_empty_hint),
                modifier = Modifier.fillMaxWidth(),
                color = if (EntryRules.canSave(body, visuals.size, feelingLocked && feeling.isNotBlank())) Quiet else Green,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(30.dp))
        }
    }
    }

    extraUris?.takeUnless { LocalImprintsLocked.current }?.let { uris ->
        val remaining = (EntryRules.MAX_VISUALS - visuals.size).coerceAtLeast(0)
        AlertDialog(onDismissRequest = { extraUris = null }, title = { Text(stringResource(R.string.editor_max_images_title)) },
            text = {
                Text(
                    stringResource(R.string.editor_max_images_message, uris.size, remaining, remaining)
                )
            },
            confirmButton = { TextButton(onClick = { extraUris = null; prepareUris(uris.take(remaining)) }) { Text(stringResource(R.string.action_got_it)) } },
            dismissButton = { TextButton(onClick = { extraUris = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_message)) },
        confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text(stringResource(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { exitRequested = false }) { Text(stringResource(R.string.editor_keep_editing)) } })
    if (showEventTimePicker) {
        val initialParsed = runCatching { LocalDateTime.parse(eventTime) }.getOrElse { LocalDateTime.now() }
        EventTimePickerSheet(
            initial = initialParsed,
            onConfirm = { picked ->
                eventTime = "%04d-%02d-%02dT%02d:%02d".format(
                    picked.year, picked.monthValue, picked.dayOfMonth, picked.hour, picked.minute)
                showEventTimePicker = false
            },
            onDismiss = { showEventTimePicker = false },
        )
    }
    if (showAudioSourceSheet) {
        AudioSourceSheet(
            onPickFile = {
                showAudioSourceSheet = false
                audioPicker.launch(arrayOf("audio/*"))
            },
            onRecord = {
                showAudioSourceSheet = false
                launchSystemRecorder()
            },
            onRemove = if (audio != null) {
                {
                    showAudioSourceSheet = false
                    (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                    audio = null
                }
            } else null,
            onDismiss = { showAudioSourceSheet = false },
        )
    }
    if (showTagPicker) {
        TagPickerSheet(
            repository = repository,
            selectedIds = selectedTagIds,
            onToggle = { id ->
                selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
            },
            onCreate = { name ->
                // 新建的标签直接挂到当前印迹上，省一次点选
                scope.launch {
                    runCatching { repository.createTag(name) }
                        .onSuccess { newId -> selectedTagIds = selectedTagIds + newId }
                        .onFailure(report)
                }
            },
            onRename = { id, name -> scope.launch { runCatching { repository.renameTag(id, name) }.onFailure(report) } },
            onDelete = { id ->
                scope.launch {
                    runCatching { repository.deleteTag(id) }
                        .onSuccess { selectedTagIds = selectedTagIds - id }
                        .onFailure(report)
                }
            },
            onDismiss = { showTagPicker = false },
            onError = report,
        )
    }
}

/** 音频来源：从文件 / 系统录音机（不自建录音 UI） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AudioSourceSheet(
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.editor_audio_title), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Surface(
                Modifier.fillMaxWidth().clickable(onClick = onPickFile),
                shape = RoundedCornerShape(14.dp),
                color = SoftCard,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("♫", color = Ink, fontSize = 18.sp)
                    Column {
                        Text(stringResource(R.string.editor_audio_from_file), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.editor_audio_from_file_sub), color = Quiet, fontSize = 12.sp)
                    }
                }
            }
            Surface(
                Modifier.fillMaxWidth().clickable(onClick = onRecord),
                shape = RoundedCornerShape(14.dp),
                color = SoftCard,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(18.dp).background(Green, RoundedCornerShape(9.dp)))
                    Column {
                        Text(stringResource(R.string.editor_audio_recorder), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.editor_audio_recorder_sub), color = Quiet, fontSize = 12.sp)
                    }
                }
            }
            if (onRemove != null) {
                Surface(
                    Modifier.fillMaxWidth().clickable(onClick = onRemove),
                    shape = RoundedCornerShape(14.dp),
                    color = SoftCard,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.editor_audio_remove), color = Danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.action_cancel), color = Quiet, fontSize = 15.sp)
            }
        }
    }
}

/**
 * 事件时间底部弹层（对齐设计 51 风格的日期选择 + 时分）：
 * 日历选日、底部时分、回到今天 / 取消 / 确定。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EventTimePickerSheet(
    initial: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
    /** 标题/副标题可替换：写信的送达时间选择复用本弹层 */
    title: String? = null,
    subtitle: String? = null,
    showBackToToday: Boolean = true,
) {
    var date by remember(initial) { mutableStateOf(initial.toLocalDate()) }
    var hour by remember(initial) { mutableStateOf(initial.hour) }
    var minute by remember(initial) { mutableStateOf(initial.minute) }
    var viewYear by remember(initial) { mutableStateOf(initial.year) }
    var viewMonth by remember(initial) { mutableStateOf(initial.monthValue) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        // 内容（日历 + 滚轮）在小屏/大字体下会超过弹层高度：内容区可滚动，
        // 操作行固定在弹层底部，保证「取消 / 确定」永远完整可见，不被屏幕下缘裁掉。
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.88f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            Text(title ?: stringResource(R.string.editor_pick_date), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle ?: stringResource(R.string.editor_pick_date_sub), color = Quiet, fontSize = 13.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (viewMonth <= 1) { viewMonth = 12; viewYear -= 1 } else viewMonth -= 1
                }) { Text(stringResource(R.string.editor_prev_month), color = Ink, fontSize = 13.sp) }
                Text(
                    stringResource(R.string.editor_month_header, viewYear, viewMonth),
                    Modifier.weight(1f),
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = {
                    if (viewMonth >= 12) { viewMonth = 1; viewYear += 1 } else viewMonth += 1
                }) { Text(stringResource(R.string.editor_next_month), color = Ink, fontSize = 13.sp) }
            }

            val firstDay = LocalDate.of(viewYear, viewMonth, 1)
            val daysInMonth = firstDay.lengthOfMonth()
            val startOffset = (firstDay.dayOfWeek.value + 6) % 7 // Monday-first
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Text(label, Modifier.weight(1f), color = Quiet, fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                val cells = startOffset + daysInMonth
                val weeks = (cells + 6) / 7
                var dayNumber = 1
                repeat(weeks) {
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { col ->
                            val cellIndex = it * 7 + col
                            Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                if (cellIndex >= startOffset && dayNumber <= daysInMonth) {
                                    val d = dayNumber
                                    val selected = date.year == viewYear && date.monthValue == viewMonth && date.dayOfMonth == d
                                    Box(
                                        Modifier.size(36.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (selected) Green else Color.Transparent)
                                            .clickable { date = LocalDate.of(viewYear, viewMonth, d) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            stringResource(R.string.calendar_day, d),
                                            color = if (selected) Color.White else Ink, fontSize = 14.sp,
                                        )
                                    }
                                    dayNumber++
                                }
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.editor_date_selected, date.year, date.monthValue, date.dayOfMonth),
                color = Ink, fontSize = 14.sp,
            )

            // 上下滑动选择时分（滚轮）
            Row(
                Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelPicker(
                    count = 24,
                    selected = hour,
                    label = { "%02d".format(it) },
                    onSelected = { hour = it },
                    modifier = Modifier.width(72.dp),
                )
                Text(":", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                WheelPicker(
                    count = 60,
                    selected = minute,
                    label = { "%02d".format(it) },
                    onSelected = { minute = it },
                    modifier = Modifier.width(72.dp),
                )
            }
            Text(stringResource(R.string.editor_time_hint), color = Quiet, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showBackToToday) TextButton(onClick = {
                    val now = LocalDateTime.now()
                    date = now.toLocalDate(); hour = now.hour; minute = now.minute
                    viewYear = now.year; viewMonth = now.monthValue
                }) { Text(stringResource(R.string.editor_back_to_today), color = Green) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Quiet) }
                Button(
                    onClick = { onConfirm(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))) },
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}

/**
 * 竖向滚轮：上下滑动 + 吸附，循环（23→0、59→0），用于选小时/分钟。
 */
@Composable
private fun WheelPicker(
    count: Int,
    selected: Int,
    label: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: androidx.compose.ui.unit.Dp = 44.dp,
) {
    // 虚拟长列表：中间一圈作为起点，两端靠近时无声回中，实现循环滑动
    val cycles = 80
    val virtualCount = count * cycles
    val midBase = (cycles / 2) * count

    fun valueOf(virtualIndex: Int): Int {
        val m = virtualIndex % count
        return if (m < 0) m + count else m
    }

    fun firstForValue(value: Int): Int = (midBase + value - 1).coerceIn(0, virtualCount - 3)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = firstForValue(selected),
    )
    val fling = rememberSnapFlingBehavior(listState)
    // 中心项的虚拟下标 → 显示用
    val centerVirtual = listState.firstVisibleItemIndex + 1
    val centerValue = valueOf(centerVirtual)

    LaunchedEffect(listState, count) {
        var lastReported = valueOf(listState.firstVisibleItemIndex + 1)
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first ->
                // 靠近两端时回中（同一 value，不闪）
                if (first < count * 3 || first > virtualCount - count * 4) {
                    val v = valueOf(first + 1)
                    val target = firstForValue(v)
                    if (target != first) {
                        listState.scrollToItem(target)
                        return@collect
                    }
                }
                val v = valueOf(first + 1)
                if (v != lastReported) {
                    lastReported = v
                    onSelected(v)
                }
            }
    }

    // 外部改 selected（如「回到今天」）时对齐；滚动过程中 current==selected 则不跳
    LaunchedEffect(selected) {
        val current = valueOf(listState.firstVisibleItemIndex + 1)
        if (current != selected) listState.scrollToItem(firstForValue(selected))
    }

    Box(
        modifier = modifier.height(itemHeight * 3).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(PaleGreen, RoundedCornerShape(10.dp)),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(virtualCount) { vIndex ->
                val value = valueOf(vIndex)
                val active = vIndex == centerVirtual
                Box(
                    Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .clickable { onSelected(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(value),
                        color = if (active) Green else Ink,
                        fontSize = if (active) 22.sp else 17.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 媒体库文件名常为 UUID，展示时换成友好文案。@Composable 以便读取本地化文案。 */
@Composable
internal fun friendlyAudioName(raw: String): String =
    if (raw.matches(Regex("[0-9a-fA-F-]{36}"))) stringResource(R.string.audio_default_name) else raw

internal enum class EditorMetaTrailing { Chevron, Clear, Expand, ClearThenChevron }

/**
 * 编辑页元信息行（音乐/位置/感受共用）：
 * 左 40 圆角图标 · 中标题+副标题 · 右 › / 展开箭头 / ×。
 * [EditorMetaTrailing.Expand]：收起为 →，展开顺时针转 90° 成 ↓。
 */
@Composable
internal fun EditorMetaRow(
    leadingContent: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    titleColor: Color,
    onClick: () -> Unit,
    trailing: EditorMetaTrailing,
    leadingTint: Color? = null,
    onClear: (() -> Unit)? = null,
    expanded: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = leadingTint ?: Color(0xFFF3F4F6),
            modifier = Modifier.size(40.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                leadingContent()
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Quiet, fontSize = 13.sp, maxLines = 1)
            }
        }
        when (trailing) {
            EditorMetaTrailing.Chevron -> Text("›", color = Quiet, fontSize = 20.sp,
                modifier = Modifier.size(width = 24.dp, height = 40.dp)
                    .wrapContentSize(Alignment.CenterEnd))
            EditorMetaTrailing.ClearThenChevron -> {
                // 标签行用：先 ×（清空已选）再 ›（进入选择），与微信一致的收尾
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onClear?.invoke() }, modifier = Modifier.size(40.dp)) {
                        Text("×", color = Quiet, fontSize = 22.sp)
                    }
                    Text("›", color = Quiet, fontSize = 20.sp,
                        modifier = Modifier.size(width = 24.dp, height = 40.dp)
                            .wrapContentSize(Alignment.CenterEnd))
                }
            }
            EditorMetaTrailing.Clear -> {
                IconButton(onClick = { onClear?.invoke() }, modifier = Modifier.size(40.dp)) {
                    Text("×", color = Quiet, fontSize = 22.sp)
                }
            }
            EditorMetaTrailing.Expand -> {
                val rotation by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(200),
                    label = "meta-chevron",
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .wrapContentSize(Alignment.CenterEnd),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        "›",
                        color = Quiet,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .rotate(rotation)
                            .padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 已选音频：与位置同一套行布局。
 * 左 ♪ 播放/暂停 · 中标题/副标题点按更换 · 右 × 移除。
 */
@Composable
internal fun EditorAudioSelectedRow(
    file: java.io.File,
    displayName: String,
    onRemove: () -> Unit,
    onReplace: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var player by remember(file.path) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var playing by remember(file.path) { mutableStateOf(false) }
    DisposableEffect(player) {
        val current = player
        val listener = if (current != null) object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        } else null
        if (listener != null) current?.addListener(listener)
        onDispose {
            if (current != null) {
                if (listener != null) current.removeListener(listener)
                current.release()
            }
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player?.pause(); playing = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = {
                val current = player ?: androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                }.also { player = it }
                if (current.isPlaying) {
                    current.pause()
                    playing = false
                } else {
                    // 播完停在 ENDED，直接 play 不会回到开头
                    if (current.playbackState == androidx.media3.common.Player.STATE_ENDED) current.seekTo(0)
                    current.play()
                    playing = true
                }
            },
            shape = RoundedCornerShape(10.dp),
            color = PaleGreen,
            modifier = Modifier.size(40.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (playing) "❚❚" else "♪", color = Green, fontSize = 16.sp)
            }
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onReplace),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(if (playing) stringResource(R.string.audio_playing_tap_short) else stringResource(R.string.audio_tap_change), color = Quiet, fontSize = 13.sp, maxLines = 1)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Text("×", color = Quiet, fontSize = 22.sp)
        }
    }
}

/**
 * 朋友圈式编辑媒体网格：已选图在前，「+」添加框固定在末尾（满 9 张不显示）；
 * 长按拖动可调整顺序，角标 × 删除。
 */
@Composable
internal fun EditorMediaGrid(
    visuals: androidx.compose.runtime.snapshots.SnapshotStateList<MediaChoice>,
    repository: ImprintRepository,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    report: (Throwable) -> Unit,
) {
    val columns = 3
    val gap = 8.dp
    var dragIndex by remember(visuals.size) { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val canAdd = visuals.size < EntryRules.MAX_VISUALS
    val cells = visuals.size + if (canAdd) 1 else 0
    val rows = (cells + columns - 1) / columns

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        when {
                            index < visuals.size -> {
                                val choice = visuals[index]
                                val file = when (choice) {
                                    is MediaChoice.Existing -> repository.files.resolve(choice.value.relativePath)
                                    is MediaChoice.New -> choice.value.pendingFile
                                }
                                val kind = when (choice) {
                                    is MediaChoice.Existing -> choice.value.kind
                                    is MediaChoice.New -> choice.value.kind
                                }
                                val isDragging = dragIndex == index
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            if (isDragging) {
                                                scaleX = 1.05f
                                                scaleY = 1.05f
                                                translationX = dragOffset.x
                                                translationY = dragOffset.y
                                                shadowElevation = 12f
                                                alpha = 0.92f
                                            }
                                        }
                                        .pointerInput(index, visuals.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    dragIndex = index
                                                    dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount
                                                    // 拖过半格时与目标格交换
                                                    val cell = size.width.toFloat()
                                                    val from = dragIndex
                                                    if (from >= 0 && from < visuals.size) {
                                                        val dx = dragOffset.x
                                                        val dy = dragOffset.y
                                                        val col = from % columns
                                                        val rowI = from / columns
                                                        var target = -1
                                                        when {
                                                            dx > cell * 0.5f && col < columns - 1 &&
                                                                (rowI * columns + col + 1) < visuals.size -> target = from + 1
                                                            dx < -cell * 0.5f && col > 0 -> target = from - 1
                                                            dy > cell * 0.5f && (rowI + 1) * columns + col < visuals.size -> target = from + columns
                                                            dy < -cell * 0.5f && rowI > 0 -> target = from - columns
                                                        }
                                                        if (target in visuals.indices && target != from) {
                                                            val item = visuals.removeAt(from)
                                                            visuals.add(target, item)
                                                            dragIndex = target
                                                            dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                        }
                                                    }
                                                },
                                                onDragEnd = { dragIndex = -1; dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                                                onDragCancel = { dragIndex = -1; dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                                            )
                                        }
                                ) {
                                    MediaThumbnail(file, kind, Modifier.fillMaxSize())
                                    Text("×",
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .background(Color(0x66000000), RoundedCornerShape(50))
                                            .clickable { onRemove(index) }
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                        color = Color.White, fontSize = 14.sp)
                                }
                            }
                            canAdd && index == visuals.size -> {
                                Surface(
                                    Modifier.fillMaxSize().clickable(onClick = onAdd),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF3F3F3),
                                    border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                ) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Default.Add, null, tint = Color(0xFF999999), modifier = Modifier.size(36.dp))
                                        Text(stringResource(R.string.editor_media_section), color = Color(0xFF999999), fontSize = 12.sp)
                                        Text(
                                            stringResource(R.string.editor_media_counter, visuals.size),
                                            color = Color(0xFFBBBBBB), fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
