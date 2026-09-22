package com.beyondguo.imprints.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class EditorSnapshot(
    val body: String, val feeling: String, val eventTime: String, val placeName: String,
    val place: PlaceEntity?, val visuals: List<String>, val audio: String?,
)

@Composable
fun EditorPage(
    repository: ImprintRepository, id: String?, epoch: Int,
    onSaved: (String) -> Unit, onCancel: () -> Unit, onUnlock: () -> Unit,
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
    var saving by remember { mutableStateOf(false) }
    var extraUris by remember { mutableStateOf<List<Uri>?>(null) }
    var initial by remember(id) { mutableStateOf<EditorSnapshot?>(null) }
    var exitRequested by remember(id) { mutableStateOf(false) }

    fun snapshot() = EditorSnapshot(body, feeling, eventTime, placeName, selectedPlace,
        visuals.map { when (it) { is MediaChoice.Existing -> it.value.id; is MediaChoice.New -> it.value.id } },
        when (val selected = audio) { is AudioChoice.Existing -> selected.value.id; is AudioChoice.New -> selected.value.id; null -> null })
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
        if (id == null) {
            feelingLocked = repository.security.state().feelingMode == "ON" && !repository.security.isFeelingUnlocked()
            if (initial == null) initial = snapshot()
        } else {
            val detail = repository.detail(id)
            if (detail == null) { loading = false; report(IllegalStateException("印迹已不存在")); return@LaunchedEffect }
            if (!loaded) {
                body = detail.entry.body
                eventTime = detail.entry.eventLocalMinute
                placeName = detail.place?.name.orEmpty()
                selectedPlace = detail.place
                visuals.clear()
                visuals.addAll(detail.visuals.map(MediaChoice::Existing))
                audio = detail.audio?.let(AudioChoice::Existing)
            }
            val decoded = runCatching { repository.security.decodeFeeling(detail.feeling) }
            val wasBlocked = blocked
            if (!loaded || blocked) feeling = decoded.getOrNull().orEmpty()
            blocked = decoded.isFailure
            feelingLocked = repository.security.state().feelingMode == "ON" && !repository.security.isFeelingUnlocked()
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
                        else { repository.files.discard(prepared); report(IllegalStateException("图片、动图、视频最多 9 个")) }
                    }.onFailure(report)
            }
        }
    }

    fun receivedUris(uris: List<Uri>) {
        val remaining = 9 - visuals.size
        if (uris.size > remaining) extraUris = uris
        else prepareUris(uris)
    }

    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) {
        receivedUris(it)
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) receivedUris(listOf(uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.files.prepare(uri, audio = true) }.onSuccess { prepared ->
                (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                audio = AudioChoice.New(prepared)
            }.onFailure(report)
        }
    }

    if (loading) { Text("正在加载…", Modifier.padding(20.dp)); return }
    if (blocked) {
        Column(Modifier.padding(20.dp)) {
            Text("请先解锁感受，再编辑这条印迹。")
            Button(onClick = onUnlock) { Text("输入感受口令") }
            TextButton(onClick = ::requestExit) { Text("返回") }
        }
        if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前输入和新选的附件尚未保存。") },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
        return
    }

    fun saveEntry() {
        saving = true
        scope.launch {
            val draft = EntryDraft(id = id, body = body, eventLocalMinute = eventTime,
                visuals = visuals.toList(), audio = audio, feeling = feeling,
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
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前输入和新选的附件尚未保存。") },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            IconButton(onClick = ::requestExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Ink)
            }
            Text(if (id == null) "新建印迹" else "编辑印迹", Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = ::saveEntry, enabled = !saving && EntryRules.canSave(body, visuals.size,
                feelingLocked && feeling.isNotBlank()), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(if (saving) "保存中" else "保存")
            }
        }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            OutlinePanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.DateRange, null, tint = Quiet, modifier = Modifier.size(20.dp))
                Text("事件时间", Modifier.weight(1f), color = Quiet, fontSize = 14.sp)
                val parsed = runCatching { LocalDateTime.parse(eventTime) }.getOrElse { LocalDateTime.now() }
                TextButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        eventTime = "%04d-%02d-%02dT%02d:%02d".format(year, month + 1, day, parsed.hour, parsed.minute)
                    }, parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).also { dialog ->
                        activePicker = dialog
                        dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                        dialog.show()
                    }
                }) { Text(eventTime.take(10), color = Ink, fontSize = 13.sp) }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        eventTime = "${eventTime.take(10)}T%02d:%02d".format(hour, minute)
                    }, parsed.hour, parsed.minute, true).also { dialog ->
                        activePicker = dialog
                        dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                        dialog.show()
                    }
                }) { Text(eventTime.takeLast(5), color = Ink, fontSize = 13.sp) }
            }
            }
        }
        item { TextField(body, { body = it }, placeholder = { Text("此刻，想记下什么？", color = Quiet) },
            modifier = Modifier.fillMaxWidth(), minLines = 5, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 29.sp, color = Ink),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent)) }
        item { FineDivider() }
        item { SectionTitle("图片 / 动图 / 视频") }
        item {
            EditorMediaGrid(
                visuals = visuals,
                repository = repository,
                onAdd = {
                    when (EntryRules.MAX_VISUALS - visuals.size) {
                        0 -> report(IllegalStateException("最多可添加 9 张图片、动图或视频"))
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
            SectionTitle("音频")
            Spacer(Modifier.height(12.dp))
            OutlinePanel(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                    Text(if (audio == null) "＋ 添加一段音频" else "更换音频", color = Green)
                }
                if (audio != null) TextButton(onClick = {
                    (audio as? AudioChoice.New)?.let { repository.files.discard(it.value) }
                    audio = null
                }) { Text("移除", color = Quiet) }
            } }
            when (val selected = audio) {
                is AudioChoice.Existing -> InlineAudio(repository.files.resolve(selected.value.relativePath))
                is AudioChoice.New -> InlineAudio(selected.value.pendingFile)
                null -> Unit
            }
        }
        item {
            // 微信式：仅一行入口，默认不展示位置控件
            Row(
                Modifier.fillMaxWidth()
                    .clickable { showLocationPicker = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    null,
                    tint = if (selectedPlace != null || placeName.isNotBlank()) Green else Quiet,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    selectedPlace?.name?.takeIf { it.isNotBlank() }
                        ?: placeName.trim().takeIf { it.isNotBlank() }
                        ?: "所在位置",
                    Modifier.weight(1f),
                    color = if (selectedPlace != null || placeName.isNotBlank()) Ink else Quiet,
                    fontSize = 15.sp,
                )
                Text("›", color = Quiet, fontSize = 20.sp)
            }
        }
        item {
            SectionTitle("感受")
            Spacer(Modifier.height(5.dp))
            Text("可选 · 默认本机保存 · 浏览时需刮开", color = Quiet, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (feelingLocked) {
                Button(onClick = onUnlock) { Text("解锁后填写感受") }
            } else OutlinedTextField(feeling, { feeling = it }, placeholder = { Text("写下这一刻的心情") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green))
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    }

    extraUris?.takeUnless { LocalImprintsLocked.current }?.let { uris ->
        val remaining = (EntryRules.MAX_VISUALS - visuals.size).coerceAtLeast(0)
        AlertDialog(onDismissRequest = { extraUris = null }, title = { Text("最多可添加 9 张") },
            text = { Text("本次选了 ${uris.size} 张，最多还可以选择 $remaining 张。是否加入前 $remaining 张？") },
            confirmButton = { TextButton(onClick = { extraUris = null; prepareUris(uris.take(remaining)) }) { Text("我知道了") } },
            dismissButton = { TextButton(onClick = { extraUris = null }) { Text("取消") } })
    }
    if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
        title = { Text("放弃未保存的修改？") },
        text = { Text("当前输入和新选的附件尚未保存。") },
        confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
}

/**
 * 朋友圈式编辑媒体网格：已选图在前，「+」添加框固定在末尾（满 9 张不显示）；
 * 长按拖动可调整顺序，角标 × 删除。
 */
@Composable
private fun EditorMediaGrid(
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
                                        Text("${visuals.size}/9", color = Color(0xFF999999), fontSize = 12.sp)
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
