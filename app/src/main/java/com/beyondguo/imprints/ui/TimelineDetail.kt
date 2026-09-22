package com.beyondguo.imprints.ui

import android.app.Dialog
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
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
    scrollToId: String? = null, onScrollConsumed: () -> Unit = {},
    onOpenPlace: (com.beyondguo.imprints.data.PlaceEntity) -> Unit = {},
) {
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
    var showTimeTravel by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showTimeTravel) { showTimeTravel = false }
    LaunchedEffect(entries, epoch) {
        details = entries.mapNotNull { repository.detail(it.id)?.let { detail -> it.id to detail } }.toMap()
    }
    val indexes = buildTimelineIndexes(entries.map { it.id to it.eventLocalMinute.take(7) })
    val monthsWithEntries = entries.map { it.eventLocalMinute.take(7) }.toSet()
    val daysWithEntries = entries.map { it.eventLocalMinute.take(10) }.toSet()

    fun scrollToEntry(match: com.beyondguo.imprints.data.EntryEntity?) {
        if (match == null) return
        scope.launch { listState.scrollToItem(indexes[match.id] ?: 0) }
    }

    LaunchedEffect(scrollToId, entries) {
        val id = scrollToId ?: return@LaunchedEffect
        val index = indexes[id]
        if (index != null) listState.scrollToItem(index)
        onScrollConsumed()
    }

    if (showTimeTravel && entries.isNotEmpty()) {
        TimeTravelPage(
            monthsWithEntries = monthsWithEntries,
            daysWithEntries = daysWithEntries,
            onSelectMonth = { year, month ->
                val target = "%04d-%02d".format(year, month)
                val match = entries.firstOrNull { it.eventLocalMinute.take(7) <= target } ?: entries.last()
                showTimeTravel = false
                scrollToEntry(match)
            },
            onSelectDay = { day ->
                val target = day.toString()
                val match = entries.firstOrNull { it.eventLocalMinute.take(10) <= target } ?: entries.last()
                showTimeTravel = false
                scrollToEntry(match)
            },
            onBack = { showTimeTravel = false },
        )
        return
    }

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
                Text("留下你的第一条印迹", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("经历、见闻，或某一刻的感受。", color = Quiet, fontSize = 14.sp)
            }
        }
        var previousMonth = ""
        entries.forEach { entry ->
            val month = entry.eventLocalMinute.take(7)
            if (month != previousMonth) {
                // 对齐设计稿 20：吸顶栏 = 月份文字 + 右侧日历（时间穿梭），不再另起定位条
                stickyHeader(key = "month-$month") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            displayMonth(month),
                            Modifier.weight(1f),
                            color = Quiet,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = { showTimeTravel = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.DateRange, "按时间定位", tint = Quiet, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                previousMonth = month
            }
            item(key = entry.id) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 15.dp)
                        .clickable { onOpen(entry.id) },
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.eventLocalMinute.substring(8, 10), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            val date = runCatching { LocalDate.parse(entry.eventLocalMinute.take(10)) }.getOrNull()
                            val weekday = date?.dayOfWeek?.value?.let { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it - 1] }.orEmpty()
                            Text("$weekday · ${entry.eventLocalMinute.takeLast(5)}", color = Quiet, fontSize = 13.sp)
                            // 设计 16：位置跟在时间后同一行
                            details[entry.id]?.place?.let { place ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.clickable { onOpenPlace(place) },
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        null,
                                        tint = Color(0xFF07C160),
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(place.name, color = Color(0xFF07C160), fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }
                        if (entry.body.isNotBlank()) {
                            var expanded by remember(entry.id) { mutableStateOf(false) }
                            Text(entry.body, Modifier.fillMaxWidth(),
                                color = Ink, fontSize = 17.sp, lineHeight = 27.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 2)
                            if (entry.body.length > 80) Text(if (expanded) "收起" else "展开",
                                Modifier.clickable { expanded = !expanded }, color = Green, fontSize = 14.sp)
                        }
                        details[entry.id]?.let { detail ->
                            if (detail.visuals.isNotEmpty()) MomentsMediaGrid(
                                detail.visuals.map {
                                    repository.files.resolve(it.relativePath) to it.kind
                                },
                                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            )
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
    onOpenPlace: (com.beyondguo.imprints.data.PlaceEntity) -> Unit = {},
    recycleActions: (() -> Unit)? = null,
) {
    var detail by remember(id) { mutableStateOf<EntryDetail?>(null) }
    var confirmRecycle by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, epoch) { detail = repository.detail(id) }
    val value = detail
    if (value == null) { Text("正在加载…", Modifier.padding(20.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val date = value.entry.eventLocalMinute
            val dateLabel = runCatching {
                val d = LocalDateTime.parse(date)
                val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
                "${d.year}年${d.monthValue}月${d.dayOfMonth}日 · $weekday"
            }.getOrElse { date.take(10) }
            Text(dateLabel, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(date.takeLast(5), color = Quiet, fontSize = 14.sp)
        } }
        if (value.entry.body.isNotBlank()) item { Text(value.entry.body, color = Ink, fontSize = 18.sp, lineHeight = 30.sp) }
        value.place?.let { place -> item {
            // 设计 49：先进入位置详情，再选择导航
            Text("⌖  ${place.name}", Modifier.clickable { onOpenPlace(place) }, color = Green, fontSize = 14.sp)
        } }
        if (value.visuals.isNotEmpty()) item {
            // 约束媒体区高度，避免与下方音频/感受重叠
            MomentsMediaGrid(
                value.visuals.map { repository.files.resolve(it.relativePath) to it.kind },
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
            )
        }
        value.audio?.let { audio -> item { InlineAudio(repository.files.resolve(audio.relativePath)) } }
        value.feeling?.let { feeling -> item { FeelingCard(feeling, repository, epoch, onUnlock, onBiometric) } }
        item { FineDivider() }
        item { Text(formatRecordedAt(value.entry.createdAtMs), color = Quiet, fontSize = 12.sp) }
        item { Text(formatUpdatedAt(value.entry.updatedAtMs), color = Quiet, fontSize = 12.sp) }
        if (recycleActions != null) {
            item { Text(
                (value.entry.deletedAtMs?.let { RecycleCountdown(it) } ?: "") + "\n到期后自动彻底删除",
                color = Quiet, fontSize = 13.sp, lineHeight = 20.sp,
            ) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = recycleActions, shape = RoundedCornerShape(12.dp)) { Text("恢复印迹") }
                TextButton(onClick = { confirmRecycle = true }) { Text("立即彻底删除", color = Quiet) }
            } }
        } else {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onEdit, shape = RoundedCornerShape(12.dp)) { Text("编辑印迹") }
                TextButton(onClick = { confirmRecycle = true }) { Text("移入回收站", color = Quiet) }
            } }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    if (confirmRecycle && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { confirmRecycle = false },
        title = { Text(if (recycleActions != null) "立即彻底删除？" else "移入回收站？") },
        text = { if (recycleActions != null) Text("删除后无法恢复，正文、感受\n和全部附件都会被永久移除。")
            else Text("移入后可在 7 天内恢复。\n到期后将自动彻底删除。") },
        confirmButton = { TextButton(onClick = { confirmRecycle = false; onDelete() }) {
            Text(if (recycleActions != null) "立即删除" else "移入回收站") } },
        dismissButton = { TextButton(onClick = { confirmRecycle = false }) { Text("取消") } })
}

private fun formatRecordedAt(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).let {
        "记录于 ${it.monthValue}月${it.dayOfMonth}日 %02d:%02d".format(it.hour, it.minute)
    }

private fun formatUpdatedAt(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).let {
        "更新于 ${it.monthValue}月${it.dayOfMonth}日 %02d:%02d".format(it.hour, it.minute)
    }

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
    var phase by remember(text, epoch) { mutableStateOf(ScratchPhase.Covered) }
    var showStars by remember(text, epoch) { mutableStateOf(false) }
    LaunchedEffect(showStars) {
        if (showStars) { delay(950); showStars = false }
    }
    LaunchedEffect(phase) {
        if (phase == ScratchPhase.Fading) {
            delay(450)
            phase = ScratchPhase.Revealed
        }
    }
    val cellPx = with(LocalDensity.current) { 16.dp.toPx() }
    val eraseRadius = with(LocalDensity.current) { 15.dp.toPx() }
    val covered = phase != ScratchPhase.Revealed

    // 设计稿：矮条未刮开（84→72dp），刮开后按内容自适应
    val boxModifier = if (phase == ScratchPhase.Revealed) {
        Modifier.fillMaxWidth().background(PaleGreen, RoundedCornerShape(12.dp))
    } else {
        Modifier.fillMaxWidth().height(72.dp).background(PaleGreen, RoundedCornerShape(12.dp))
            .onSizeChanged { size = it }
    }

    Box(boxModifier) {
        if (phase == ScratchPhase.Revealed) {
            Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                color = Ink, lineHeight = 25.sp)
        } else {
            // 涂层内容始终铺满文字区域，便于刮除时露出正文
            if (phase == ScratchPhase.Fading) {
                Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    color = Ink, lineHeight = 25.sp)
            }
            Canvas(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = if (phase == ScratchPhase.Fading) 0f else 1f
                    }
                    .pointerInput(text, epoch, size) {
                        detectDragGestures(
                            onDragStart = {
                                points.add(it)
                                visited.clear()
                                markCells(it, cellPx, size, visited)
                            },
                            onDrag = { change, _ ->
                                if (phase == ScratchPhase.Covered) {
                                    points.add(change.position)
                                    markCells(change.position, cellPx, size, visited)
                                    val columns = (size.width / cellPx).toInt() + 1
                                    val rows = (size.height / cellPx).toInt() + 1
                                    val total = columns * rows
                                    if (total > 0 && visited.size.toFloat() / total > 0.55f) {
                                        phase = ScratchPhase.Fading
                                        showStars = true
                                    }
                                    change.consume()
                                }
                            },
                        )
                    }
            ) {
                // 设计稿：品牌绿实底 + 细斜纹 + 四角星高光（无文字）
                drawRect(Green)
                val stripe = Color.White.copy(alpha = 0.14f)
                val stripeStep = 9.dp.toPx()
                var x = -size.height.toFloat()
                while (x < size.width + size.height) {
                    drawLine(stripe, Offset(x, 0f), Offset(x + size.height, size.height.toFloat()),
                        strokeWidth = 3.dp.toPx())
                    x += stripeStep
                }
                // 四枚四角星（相对比例，对齐原型 paintScratch）
                val stars = listOf(
                    Triple(0.16f, 0.35f, 0.11f),
                    Triple(0.44f, 0.70f, 0.08f),
                    Triple(0.70f, 0.28f, 0.13f),
                    Triple(0.88f, 0.60f, 0.07f),
                )
                val starColor = Color.White.copy(alpha = 0.35f)
                for ((fx, fy, fr) in stars) {
                    val cx = size.width * fx
                    val cy = size.height * fy
                    val r = minOf(size.width, size.height) * fr
                    drawSparkle(cx, cy, r, starColor)
                }
                // 刮除轨迹
                points.firstOrNull()?.let { drawCircle(Color.Transparent, eraseRadius, it, blendMode = BlendMode.Clear) }
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color.Transparent, a, b, strokeWidth = eraseRadius * 2,
                        cap = StrokeCap.Round, blendMode = BlendMode.Clear)
                }
            }
        }
        // 揭开瞬间：16 粒星星爆发（金/绿/白），约 950ms
        if (showStars) ScratchBurst(modifier = Modifier.matchParentSize())
    }
}

private enum class ScratchPhase { Covered, Fading, Revealed }

private fun markCells(p: Offset, cellPx: Float, size: IntSize, out: MutableSet<Pair<Int, Int>>) {
    val x = (p.x / cellPx).toInt()
    val y = (p.y / cellPx).toInt()
    val columns = (size.width / cellPx).toInt() + 1
    val rows = (size.height / cellPx).toInt() + 1
    for (dx in -1..1) for (dy in -1..1) {
        val cellX = x + dx
        val cellY = y + dy
        if (cellX in 0 until columns && cellY in 0 until rows) out.add(cellX to cellY)
    }
}

/** 四角星：对角凹弧连线（对齐设计稿 sparkle path） */
private fun DrawScope.drawSparkle(
    cx: Float, cy: Float, r: Float, color: Color,
) {
    val path = Path().apply {
        moveTo(cx, cy - r)
        quadraticBezierTo(cx, cy, cx + r, cy)
        quadraticBezierTo(cx, cy, cx, cy + r)
        quadraticBezierTo(cx, cy, cx - r, cy)
        quadraticBezierTo(cx, cy, cx, cy - r)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun ScratchBurst(modifier: Modifier = Modifier) {
    // 与原型 burstScratch 一致：16 粒，#F2CE7B / #07C160 / #FFFFFF
    val colors = listOf(Color(0xFFF2CE7B), Green, Color.White)
    val particles = remember {
        List(16) { i ->
            BurstParticle(
                color = colors[i % 3],
                size = 6f + ((i * 37) % 9),
                dx = ((i * 53) % 221 - 110).toFloat(),
                dy = -10f - ((i * 41) % 131).toFloat(),
            )
        }
    }
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing),
        label = "scratch-burst",
    )
    Canvas(modifier) {
        val originX = size.width * 0.45f
        val originY = size.height * 0.5f
        for (p in particles) {
            val x = originX + p.dx * progress
            val y = originY + p.dy * progress
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val s = p.size.dp.toPx() * (0.6f + 0.4f * (1f - progress))
            drawCircle(p.color.copy(alpha = alpha * p.color.alpha), radius = s / 2f, center = Offset(x, y))
        }
    }
}

private data class BurstParticle(val color: Color, val size: Float, val dx: Float, val dy: Float)

fun RecycleCountdown(deletedAtMs: Long): String =
    EntryRules.recycleRemainingLabel(deletedAtMs, System.currentTimeMillis())
