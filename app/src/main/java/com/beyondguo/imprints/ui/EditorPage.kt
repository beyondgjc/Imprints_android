package com.beyondguo.imprints.ui

import android.app.Dialog
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    var showEventTimePicker by remember(id) { mutableStateOf(false) }

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
            TextButton(onClick = ::requestExit) { Text("取消") }
        }
        if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
            title = { Text("有内容尚未保存") },
            text = { Text("离开后，本次未保存的修改\n将不会保留。") },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃修改") } },
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
            title = { Text("有内容尚未保存") },
            text = { Text("离开后，本次未保存的修改\n将不会保留。") },
            confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
        return
    }

    // adjustResize（系统缩窗）+ imePadding（Compose 再按 IME 收，edge-to-edge 下必要）
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = ::requestExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Text("取消", color = Ink, fontSize = 16.sp)
            }
            Text(if (id == null) "新建印迹" else "编辑印迹", Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = ::saveEntry, enabled = !saving && EntryRules.canSave(body, visuals.size,
                feelingLocked && feeling.isNotBlank()), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(if (saving) "保存中" else "保存")
            }
        }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val listBottomPad = with(density) {
        val extra = if (imeBottomPx > 0) (imeBottomPx * 0.15f).toDp() else 0.dp
        24.dp + extra
    }
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = listBottomPad),
    ) {
        // 正文 → 图片/媒体 → 音频 → 位置 → 事件时间 → 感受
        item { TextField(body, { body = it }, placeholder = { Text("发生了什么值得记住的事？", color = Quiet) },
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
            when (val selected = audio) {
                null -> EditorMetaRow(
                    leadingContent = { Text("♪", color = Quiet, fontSize = 18.sp) },
                    title = "添加音乐",
                    subtitle = null,
                    titleColor = Quiet,
                    onClick = { audioPicker.launch(arrayOf("audio/*")) },
                    trailing = EditorMetaTrailing.Chevron,
                )
                is AudioChoice.Existing -> {
                    val file = repository.files.resolve(selected.value.relativePath)
                    EditorAudioSelectedRow(
                        file = file,
                        displayName = friendlyAudioName(file.nameWithoutExtension),
                        onRemove = { audio = null },
                        onReplace = { audioPicker.launch(arrayOf("audio/*")) },
                    )
                }
                is AudioChoice.New -> EditorAudioSelectedRow(
                    file = selected.value.pendingFile,
                    displayName = friendlyAudioName(selected.value.pendingFile.nameWithoutExtension),
                    onRemove = {
                        repository.files.discard(selected.value)
                        audio = null
                    },
                    onReplace = { audioPicker.launch(arrayOf("audio/*")) },
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
                    title = placeTitle!!,
                    subtitle = selectedPlace?.address?.takeIf { it.isNotBlank() } ?: "点按更换位置",
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
                    title = "所在位置",
                    subtitle = null,
                    titleColor = Quiet,
                    onClick = { showLocationPicker = true },
                    trailing = EditorMetaTrailing.Chevron,
                )
            }
        }
        // 事件时间：与音乐/位置同一套 Meta 行，位于位置下方
        item {
            EditorMetaRow(
                leadingContent = {
                    Icon(Icons.Default.DateRange, null, tint = Quiet, modifier = Modifier.size(20.dp))
                },
                title = "事件时间",
                subtitle = "${eventTime.take(10)} · ${eventTime.takeLast(5)}",
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
            )
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("文字或图片 / 动图 / 视频，至少填写一项",
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
        AlertDialog(onDismissRequest = { extraUris = null }, title = { Text("最多可添加 9 张") },
            text = { Text("本次选了 ${uris.size} 张，最多还可以选择 $remaining 张。是否加入前 $remaining 张？") },
            confirmButton = { TextButton(onClick = { extraUris = null; prepareUris(uris.take(remaining)) }) { Text("我知道了") } },
            dismissButton = { TextButton(onClick = { extraUris = null }) { Text("取消") } })
    }
    if (exitRequested && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { exitRequested = false },
        title = { Text("有内容尚未保存") },
        text = { Text("离开后，本次未保存的修改\n将不会保留。") },
        confirmButton = { TextButton(onClick = { exitRequested = false; discardPending(); onCancel() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { exitRequested = false }) { Text("继续编辑") } })
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
}

/**
 * 事件时间底部弹层（对齐设计 51 风格的日期选择 + 时分）：
 * 日历选日、底部时分、回到今天 / 取消 / 确定。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EventTimePickerSheet(
    initial: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
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
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("选择事件日期", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("选择事情发生的那一天", color = Quiet, fontSize = 13.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (viewMonth <= 1) { viewMonth = 12; viewYear -= 1 } else viewMonth -= 1
                }) { Text("‹ 上个月", color = Ink, fontSize = 13.sp) }
                Text(
                    "$viewYear 年 · $viewMonth 月",
                    Modifier.weight(1f),
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = {
                    if (viewMonth >= 12) { viewMonth = 1; viewYear += 1 } else viewMonth += 1
                }) { Text("下个月 ›", color = Ink, fontSize = 13.sp) }
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
                                        Text("$d", color = if (selected) Color.White else Ink, fontSize = 14.sp)
                                    }
                                    dayNumber++
                                }
                            }
                        }
                    }
                }
            }

            Text("已选：${date.year}年${date.monthValue}月${date.dayOfMonth}日", color = Ink, fontSize = 14.sp)

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
            Text("上下滑动选择小时和分钟", color = Quiet, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val now = LocalDateTime.now()
                    date = now.toLocalDate(); hour = now.hour; minute = now.minute
                    viewYear = now.year; viewMonth = now.monthValue
                }) { Text("回到今天", color = Green) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消", color = Quiet) }
                androidx.compose.material3.Button(
                    onClick = { onConfirm(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))) },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("确定") }
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
        androidx.compose.foundation.lazy.LazyColumn(
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

/** 媒体库文件名常为 UUID，展示时换成友好文案 */
private fun friendlyAudioName(raw: String): String =
    if (raw.matches(Regex("[0-9a-fA-F-]{36}"))) "音频记录" else raw

internal enum class EditorMetaTrailing { Chevron, Clear, Expand }

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
private fun EditorAudioSelectedRow(
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
                if (current.isPlaying) { current.pause(); playing = false }
                else { current.play(); playing = true }
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
            Text(if (playing) "播放中 · 点按更换" else "音频 · 点按更换", color = Quiet, fontSize = 13.sp, maxLines = 1)
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
                                        Text("图片/视频", color = Color(0xFF999999), fontSize = 12.sp)
                                        Text("${visuals.size}/9", color = Color(0xFFBBBBBB), fontSize = 11.sp)
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
