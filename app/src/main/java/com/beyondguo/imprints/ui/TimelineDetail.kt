package com.beyondguo.imprints.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyondguo.imprints.data.EntryDetail
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.FeelingEntity
import com.beyondguo.imprints.data.ImprintRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelinePage(
    entries: List<EntryEntity>, repository: ImprintRepository, epoch: Int,
    onOpen: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit,
    onUnlock: () -> Unit, onBiometric: () -> Unit,
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
    val listState = rememberLazyListState()
    var details by remember { mutableStateOf(emptyMap<String, EntryDetail>()) }
    LaunchedEffect(entries, epoch) {
        details = entries.mapNotNull { repository.detail(it.id)?.let { detail -> it.id to detail } }.toMap()
    }
    val indexes = buildTimelineIndexes(entries.map { it.id to it.eventLocalMinute.take(7) })
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 28.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的印迹", Modifier.weight(1f), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.MoreVert, "设置", tint = Quiet) }
                }
                OutlinePanel(Modifier.fillMaxWidth().clickable(onClick = onSearch)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Search, null, tint = Quiet)
                        Text("搜索印迹正文", color = Quiet, fontSize = 15.sp)
                    }
                }
            }
        }
        if (entries.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("写下第一枚印迹", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("记下今天值得留下的瞬间", color = Quiet, fontSize = 14.sp)
            }
        }
        if (entries.isNotEmpty()) item(key = "locators") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(displayMonth(entries.first().eventLocalMinute.take(7)), Modifier.weight(1f),
                    color = Quiet, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                val today = LocalDate.now()
                DatePickerDialog(context, { _, year, month, _ ->
                    val target = "%04d-%02d".format(year, month + 1)
                    val match = entries.firstOrNull { it.eventLocalMinute.take(7) <= target } ?: entries.last()
                    scope.launch { listState.scrollToItem(indexes[match.id] ?: 0) }
                }, today.year, today.monthValue - 1, today.dayOfMonth).also { dialog ->
                    activePicker = dialog
                    dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                    dialog.show()
                }
                }) { Text("月份", color = Quiet, fontSize = 12.sp) }
                IconButton(onClick = {
                val today = LocalDate.now()
                DatePickerDialog(context, { _, year, month, day ->
                    val target = "%04d-%02d-%02d".format(year, month + 1, day)
                    val match = entries.firstOrNull { it.eventLocalMinute.take(10) <= target } ?: entries.last()
                    scope.launch { listState.scrollToItem(indexes[match.id] ?: 0) }
                }, today.year, today.monthValue - 1, today.dayOfMonth).also { dialog ->
                    activePicker = dialog
                    dialog.setOnDismissListener { if (activePicker === dialog) activePicker = null }
                    dialog.show()
                }
                }) { Icon(Icons.Default.DateRange, "按日定位", tint = Quiet) }
            }
        }
        var previousMonth = ""
        entries.forEach { entry ->
            val month = entry.eventLocalMinute.take(7)
            if (month != previousMonth) {
                stickyHeader(key = "month-$month") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Text(displayMonth(month), color = Quiet, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                previousMonth = month
            }
            item(key = entry.id) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.eventLocalMinute.substring(8, 10), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            val date = runCatching { LocalDate.parse(entry.eventLocalMinute.take(10)) }.getOrNull()
                            val weekday = date?.dayOfWeek?.value?.let { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it - 1] }.orEmpty()
                            Text("$weekday · ${entry.eventLocalMinute.takeLast(5)}", color = Quiet, fontSize = 13.sp)
                        }
                        details[entry.id]?.place?.let { place ->
                            Text(place.name, Modifier.clickable(enabled = place.latitude != null && place.longitude != null) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("geo:0,0?q=${place.latitude},${place.longitude}"))) }
                                    .onFailure { Toast.makeText(context, "未找到可用的地图应用", Toast.LENGTH_SHORT).show() }
                            }, color = Color(0xFF07C160))
                        }
                        if (entry.body.isNotBlank()) {
                            var expanded by remember(entry.id) { mutableStateOf(false) }
                            Text(entry.body, Modifier.fillMaxWidth().clickable { onOpen(entry.id) },
                                color = Ink, fontSize = 17.sp, lineHeight = 27.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 2)
                            if (entry.body.length > 80) Text(if (expanded) "收起" else "展开全文",
                                Modifier.clickable { expanded = !expanded }, color = Green, fontSize = 14.sp)
                        }
                        details[entry.id]?.let { detail ->
                            if (detail.visuals.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(detail.visuals, key = { it.id }) { media ->
                                    MediaThumbnail(repository.files.resolve(media.relativePath), media.kind)
                                }
                            }
                            detail.audio?.let { InlineAudio(repository.files.resolve(it.relativePath)) }
                            if (detail.feeling != null) FeelingCard(detail.feeling, repository, epoch, onUnlock, onBiometric)
                        }
                    FineDivider()
                }
            }
        }
    }
}

private fun displayMonth(value: String): String = runCatching {
    YearMonth.parse(value).let { "${it.year}年${it.monthValue}月" }
}.getOrElse { value }

@Composable
fun DetailPage(
    repository: ImprintRepository, id: String, epoch: Int,
    onEdit: () -> Unit, onDelete: () -> Unit, onUnlock: () -> Unit, onBiometric: () -> Unit,
) {
    val context = LocalContext.current
    var detail by remember(id) { mutableStateOf<EntryDetail?>(null) }
    var confirmRecycle by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, epoch) { detail = repository.detail(id) }
    val value = detail
    if (value == null) { Text("正在加载…", Modifier.padding(20.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val date = value.entry.eventLocalMinute
            val dateLabel = runCatching { LocalDateTime.parse(date).format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }
                .getOrElse { date.take(10) }
            Text(dateLabel, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(date.takeLast(5), color = Quiet, fontSize = 14.sp)
        } }
        if (value.entry.body.isNotBlank()) item { Text(value.entry.body, color = Ink, fontSize = 18.sp, lineHeight = 30.sp) }
        value.place?.let { place -> item {
            val coordinates = if (place.latitude != null && place.longitude != null) "${place.latitude},${place.longitude}" else null
            Text("⌖  ${place.name}", Modifier.clickable(enabled = coordinates != null) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$coordinates"))) }
                    .onFailure { Toast.makeText(context, "未找到可用的地图应用", Toast.LENGTH_SHORT).show() }
            }, color = Green, fontSize = 14.sp)
        } }
        if (value.visuals.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                value.visuals.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { MediaThumbnail(repository.files.resolve(it.relativePath), it.kind) }
                    }
                }
            }
        }
        value.audio?.let { audio -> item { InlineAudio(repository.files.resolve(audio.relativePath)) } }
        value.feeling?.let { feeling -> item { FeelingCard(feeling, repository, epoch, onUnlock, onBiometric) } }
        item { FineDivider() }
        item { Text("记录于 ${formatRecordedAt(value.entry.createdAtMs)}", color = Quiet, fontSize = 12.sp) }
        item { Text("更新于 ${formatRecordedAt(value.entry.updatedAtMs)}", color = Quiet, fontSize = 12.sp) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onEdit, shape = RoundedCornerShape(12.dp)) { Text("编辑印迹") }
            TextButton(onClick = { confirmRecycle = true }) { Text("移入回收站", color = Quiet) }
        } }
        item { Spacer(Modifier.height(32.dp)) }
    }
    if (confirmRecycle && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { confirmRecycle = false },
        title = { Text("移入回收站？") },
        text = { Text("7 天内可在设置中的回收站恢复。") },
        confirmButton = { TextButton(onClick = { confirmRecycle = false; onDelete() }) { Text("移入回收站") } },
        dismissButton = { TextButton(onClick = { confirmRecycle = false }) { Text("取消") } })
}

private fun formatRecordedAt(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))

@Composable
fun FeelingCard(
    feeling: FeelingEntity, repository: ImprintRepository, epoch: Int,
    onUnlock: () -> Unit, onBiometric: () -> Unit,
) {
    var text by remember(feeling, epoch) { mutableStateOf<String?>(null) }
    var encrypted by remember(feeling, epoch) { mutableStateOf(false) }
    var biometricEnabled by remember(feeling, epoch) { mutableStateOf(false) }
    LaunchedEffect(feeling, epoch) {
        val state = repository.security.state()
        encrypted = state.feelingMode == "ON"
        biometricEnabled = state.feelingBiometricEnvelope != null
        text = runCatching { repository.security.decodeFeeling(feeling) }.getOrNull()
    }
    if (encrypted && text == null) {
        OutlinePanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("感受已加密", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("解锁后仍需亲手刮开涂层", color = Quiet, fontSize = 12.sp)
                Row {
                    TextButton(onClick = onUnlock) { Text("6 位口令") }
                    if (biometricEnabled) TextButton(onClick = onBiometric) { Text("生物识别") }
                }
            }
        }
    } else text?.let { ScratchFeeling(it, epoch) }
}

@Composable
private fun ScratchFeeling(text: String, epoch: Int) {
    val points = remember(text, epoch) { mutableStateListOf<Offset>() }
    val visited = remember(text, epoch) { mutableSetOf<Pair<Int, Int>>() }
    var size by remember(text, epoch) { mutableStateOf(IntSize.Zero) }
    var revealed by remember(text, epoch) { mutableStateOf(false) }
    var showStars by remember(text, epoch) { mutableStateOf(false) }
    LaunchedEffect(showStars) {
        if (showStars) { delay(900); showStars = false }
    }
    val cellPx = with(LocalDensity.current) { 24.dp.toPx() }
    Box(Modifier.fillMaxWidth().heightIn(min = 110.dp).background(PaleGreen, RoundedCornerShape(16.dp))
        .onSizeChanged { size = it }) {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = Ink, lineHeight = 25.sp)
        if (!revealed) {
            Canvas(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(text, epoch, size) {
                    detectDragGestures(onDragStart = { points.add(it) }) { change, _ ->
                        points.add(change.position)
                        val x = (change.position.x / cellPx).toInt()
                        val y = (change.position.y / cellPx).toInt()
                        val columns = (size.width / cellPx).toInt() + 1
                        val rows = (size.height / cellPx).toInt() + 1
                        for (dx in -1..1) for (dy in -1..1) {
                            val cellX = x + dx
                            val cellY = y + dy
                            if (cellX in 0 until columns && cellY in 0 until rows) visited.add(cellX to cellY)
                        }
                        val total = columns * rows
                        if (total > 0 && visited.size >= total * 0.55) {
                            revealed = true
                            showStars = true
                        }
                        change.consume()
                    }
                }) {
                drawRect(Green)
                val stripe = Color.White.copy(alpha = 0.11f)
                var x = -size.height.toFloat()
                while (x < size.width + size.height) {
                    drawLine(stripe, Offset(x, 0f), Offset(x + size.height, size.height.toFloat()),
                        strokeWidth = 8.dp.toPx())
                    x += 72.dp.toPx()
                }
                points.firstOrNull()?.let { drawCircle(Color.Transparent, 21.dp.toPx(), it, blendMode = BlendMode.Clear) }
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color.Transparent, a, b, strokeWidth = 42.dp.toPx(), cap = StrokeCap.Round, blendMode = BlendMode.Clear)
                }
            }
            Text("✦  刮开感受  ✦", Modifier.align(Alignment.Center),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showStars) Text("✨", Modifier.align(Alignment.Center), style = MaterialTheme.typography.displayLarge)
    }
}

fun RecycleCountdown(deletedAtMs: Long): String =
    EntryRules.recycleRemainingLabel(deletedAtMs, System.currentTimeMillis())
