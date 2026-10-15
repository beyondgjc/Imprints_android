package com.beyondguo.imprints.ui

import android.app.Dialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyondguo.imprints.data.EntryDetail
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.FeelingEntity
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.PlaceEntity
import com.beyondguo.imprints.data.TagCatalog
import kotlinx.coroutines.launch

import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelinePage(
    entries: List<EntryEntity>, repository: ImprintRepository, epoch: Int,
    onOpen: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit,
    onUnlock: () -> Unit, onBiometric: () -> Unit,
    scrollToId: String? = null, onScrollConsumed: () -> Unit = {},
    onOpenPlace: (com.beyondguo.imprints.data.PlaceEntity) -> Unit = {},
    /** 已选中的标签 id；空集合 = 显示全部。状态由上层持有，便于导航往返后保留筛选。 */
    selectedTagIds: Set<String> = emptySet(),
    onToggleTag: (String) -> Unit = {},
    /** 点「全部」时清空筛选。 */
    onClearTags: () -> Unit = {},
    onCreateTag: (String) -> Unit = {},
    onError: (Throwable) -> Unit = {},
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
    var onFilterCreateTag by remember { mutableStateOf(false) }
    // 标签筛选在内存里做：条目数有限，避免为每种筛选组合再写一套 DAO 查询
    val selectableTags = repository.selectableTags.collectAsState(initial = emptyList()).value
    // chip 顺序按使用频次降序：越常用越靠左，横滚区里更容易够到。
    // 频次为 0 或并列的标签保持 selectableTags 原序（预置在前、自定义按创建时间，见 TagRules.TAG_ORDER）。
    var tagUsage by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(selectableTags.map { it.id }, entries) {
        tagUsage = repository.tagUsageCounts().toMap()
    }
    val orderedTags = remember(selectableTags, tagUsage) {
        selectableTags.sortedByDescending { tagUsage[it.id] ?: 0 }
    }
    val tagSelection = repository.entryTags.collectAsState(initial = emptyList()).value
        .groupBy({ it.entryId }, { it.tagId })
    val visibleEntries = remember(entries, tagSelection, selectedTagIds) {
        if (selectedTagIds.isEmpty()) entries
        else entries.filter { entry -> tagSelection[entry.id].orEmpty().any { it in selectedTagIds } }
    }
    androidx.activity.compose.BackHandler(enabled = showTimeTravel) { showTimeTravel = false }
    LaunchedEffect(entries, epoch) {
        details = entries.mapNotNull { repository.detail(it.id)?.let { detail -> it.id to detail } }.toMap()
    }
    val indexes = buildTimelineIndexes(visibleEntries.map { it.id to it.eventLocalMinute.take(7) })
    val monthsWithEntries = visibleEntries.map { it.eventLocalMinute.take(7) }.toSet()
    val daysWithEntries = visibleEntries.map { it.eventLocalMinute.take(10) }.toSet()

    fun scrollToEntry(match: EntryEntity?) {
        if (match == null) return
        scope.launch { listState.scrollToItem(indexes[match.id] ?: 0) }
    }

    LaunchedEffect(scrollToId, visibleEntries) {
        val id = scrollToId ?: return@LaunchedEffect
        val index = indexes[id]
        if (index != null) listState.scrollToItem(index)
        onScrollConsumed()
    }

    if (showTimeTravel && visibleEntries.isNotEmpty()) {
        TimeTravelPage(
            monthsWithEntries = monthsWithEntries,
            daysWithEntries = daysWithEntries,
            onSelectMonth = { year, month ->
                val target = "%04d-%02d".format(year, month)
                val match = visibleEntries.firstOrNull { it.eventLocalMinute.take(7) <= target } ?: visibleEntries.last()
                showTimeTravel = false
                scrollToEntry(match)
            },
            onSelectDay = { day ->
                val target = day.toString()
                val match = visibleEntries.firstOrNull { it.eventLocalMinute.take(10) <= target } ?: visibleEntries.last()
                showTimeTravel = false
                scrollToEntry(match)
            },
            onBack = { showTimeTravel = false },
        )
        return
    }

    // 下滑超过约一屏后显示「回到顶部」，叠在 FAB（+）上方
    val showBackToTop by remember {
        androidx.compose.runtime.derivedStateOf {
            listState.firstVisibleItemIndex > 2 ||
                listState.firstVisibleItemScrollOffset > 480
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 28.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.timeline_title), Modifier.weight(1f), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.MoreVert, stringResource(R.string.a11y_settings), tint = Quiet) }
                }
                // 标签筛选：单行横向滚动，首位「全部」= 未筛选，多选仍为叠加（OR）
                TagFilterRow(
                    tags = orderedTags,
                    selectedIds = selectedTagIds,
                    onToggle = onToggleTag,
                    onClear = onClearTags,
                    onCreate = { onFilterCreateTag = true },
                    createDisabled = com.beyondguo.imprints.data.TagRules.hasReachedLimit(selectableTags.size),
                )
            }
        }
        if (entries.isEmpty() || visibleEntries.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (entries.isEmpty()) stringResource(R.string.timeline_empty_title) else stringResource(R.string.timeline_filter_empty_title),
                    color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (entries.isEmpty()) stringResource(R.string.timeline_empty_sub) else stringResource(R.string.timeline_filter_empty_sub),
                    color = Quiet, fontSize = 14.sp,
                )
            }
        }
        var previousMonth = ""
        visibleEntries.forEach { entry ->
            val month = entry.eventLocalMinute.take(7)
            if (month != previousMonth) {
                // 对齐设计稿 20：吸顶栏 = 月份文字 + 右侧日历（时间穿梭），不再另起定位条
                stickyHeader(key = "month-$month") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            displayMonth(month),
                            Modifier
                                .weight(1f)
                                .padding(start = 6.dp),
                            color = Quiet,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        // 吸顶：搜索在日历左侧
                        IconButton(
                            onClick = onSearch,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Search, stringResource(R.string.a11y_search), tint = Quiet, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { showTimeTravel = true },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.DateRange, stringResource(R.string.timetravel_title), tint = Quiet, modifier = Modifier.size(20.dp))
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
                            val weekday = date?.dayOfWeek?.value?.let { weekdayNames()[it - 1] }.orEmpty()
                            Text(
                                stringResource(R.string.detail_time_meta, weekday, entry.eventLocalMinute.takeLast(5)),
                                color = Quiet, fontSize = 13.sp,
                            )
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
                            // 知乎式：截断时「展开 ∨」浮在末行右下角（背景遮罩）；
                            // 展开后正文完整显示，「收起 ∧」另起一行右对齐，不遮盖末行文字。
                            var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
                            var clamped by rememberSaveable(entry.id) { mutableStateOf(false) }
                            if (expanded) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                                    Text(
                                        entry.body,
                                        Modifier.fillMaxWidth(),
                                        color = Ink, fontSize = 17.sp, lineHeight = 27.sp,
                                    )
                                    Row(
                                        Modifier
                                            .clickable { expanded = false }
                                            .padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(R.string.action_collapse),
                                            color = Green,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.a11y_collapse_body),
                                            tint = Green,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth()) {
                                    Text(
                                        entry.body,
                                        Modifier.fillMaxWidth(),
                                        onTextLayout = { result ->
                                            if (result.lineCount > 0) {
                                                clamped = result.isLineEllipsized(result.lineCount - 1)
                                            }
                                        },
                                        color = Ink, fontSize = 17.sp, lineHeight = 27.sp,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (clamped) {
                                        Row(
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .background(MaterialTheme.colorScheme.background)
                                                .clickable { expanded = true }
                                                .padding(start = 24.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                stringResource(R.string.action_expand),
                                                color = Green,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = stringResource(R.string.a11y_expand_body),
                                                tint = Green,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        details[entry.id]?.let { detail ->
                            if (detail.visuals.isNotEmpty()) MomentsMediaGrid(
                                detail.visuals.map { v ->
                                    MediaCell(
                                        file = repository.files.resolve(v.relativePath),
                                        kind = v.kind,
                                        motionVideo = v.motionVideoPath?.let { repository.files.resolve(it) },
                                    )
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

    // 回到顶部：叠在 Scaffold FAB（+）上方，下滑一段后出现
    if (showBackToTop) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Line),
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp)
                .size(44.dp)
                .clickable {
                    scope.launch { listState.animateScrollToItem(0) }
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.a11y_back_to_top),
                    tint = Quiet,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    // 筛选栏的新建入口：这里只负责把标签建出来并让上层选中它
    if (onFilterCreateTag) {
        TagNameDialog(
            title = stringResource(R.string.tag_create_title),
            initial = "",
            confirmLabel = stringResource(R.string.action_confirm),
            onDismiss = { onFilterCreateTag = false },
            onConfirm = { name ->
                runCatching { onCreateTag(name) }.onFailure(onError)
                onFilterCreateTag = false
            },
        )
    }
    }
}

/** 时间线分组标题的月份标签。@Composable 以便读取本地化格式。 */
@Composable
private fun displayMonth(value: String): String {
    // 只包住解析，stringResource 放在外面（理由见 formatSearchDate）
    val month = runCatching { YearMonth.parse(value) }.getOrNull() ?: return value
    return stringResource(R.string.timetravel_month_full, month.year, month.monthValue)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailPage(
    repository: ImprintRepository, id: String, epoch: Int,
    onEdit: () -> Unit, onDelete: () -> Unit, onUnlock: () -> Unit, onBiometric: () -> Unit,
    onOpenPlace: (com.beyondguo.imprints.data.PlaceEntity) -> Unit = {},
    recycleActions: (() -> Unit)? = null,
    /** 标签 id → 名称。自定义标签名只在库里，所以由上层查询后传入。 */
    tagNames: Map<String, String> = emptyMap(),
) {
    var detail by remember(id) { mutableStateOf<EntryDetail?>(null) }
    var confirmRecycle by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, epoch) { detail = repository.detail(id) }
    val value = detail
    if (value == null) { Text(stringResource(R.string.state_loading), Modifier.padding(20.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val date = value.entry.eventLocalMinute
            // 只包住解析，stringResource 放在外面（理由见 formatSearchDate）
            val parsed = runCatching { LocalDateTime.parse(date) }.getOrNull()
            val dateLabel = if (parsed == null) date.take(10) else {
                val weekdayNames = weekdayNames()
                stringResource(
                    R.string.format_ymd_weekday,
                    parsed.year, parsed.monthValue, parsed.dayOfMonth,
                    weekdayNames[parsed.dayOfWeek.value - 1],
                )
            }
            Text(dateLabel, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(date.takeLast(5), color = Quiet, fontSize = 14.sp)
        } }
        if (value.entry.body.isNotBlank()) item { Text(value.entry.body, color = Ink, fontSize = 18.sp, lineHeight = 30.sp) }
        value.place?.let { place -> item {
            // 设计 49：先进入位置详情，再选择导航
            Text(stringResource(R.string.detail_place_meta, place.name), Modifier.clickable { onOpenPlace(place) }, color = Green, fontSize = 14.sp)
        } }
        if (value.tagIds.isNotEmpty()) item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                value.tagIds.forEach { tagId ->
                    TagChip(
                        text = tagNames[tagId] ?: TagCatalog.nameOf(tagId) ?: tagId,
                        selected = false,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
        if (value.visuals.isNotEmpty()) item {
            // 约束媒体区高度，避免与下方音频/感受重叠
            MomentsMediaGrid(
                value.visuals.map { v ->
                    MediaCell(
                        file = repository.files.resolve(v.relativePath),
                        kind = v.kind,
                        motionVideo = v.motionVideoPath?.let { repository.files.resolve(it) },
                    )
                },
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
                (value.entry.deletedAtMs?.let { RecycleCountdown(it) } ?: "") + "\n" + stringResource(R.string.recycle_auto_delete),
                color = Quiet, fontSize = 13.sp, lineHeight = 20.sp,
            ) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = recycleActions, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.detail_restore)) }
                TextButton(onClick = { confirmRecycle = true }) { Text(stringResource(R.string.detail_delete_now), color = Quiet) }
            } }
        } else {
            // 编辑已移到顶栏右上角（设计 41）；底部仅保留移入回收站
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmRecycle = true }) { Text(stringResource(R.string.detail_move_to_recycle), color = Quiet) }
            } }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    if (confirmRecycle && !LocalImprintsLocked.current) AlertDialog(onDismissRequest = { confirmRecycle = false },
        title = { Text(if (recycleActions != null) stringResource(R.string.delete_forever_title) else stringResource(R.string.detail_recycle_confirm_title)) },
        text = { if (recycleActions != null) Text(stringResource(R.string.delete_forever_message))
            else Text(stringResource(R.string.detail_move_to_recycle_message)) },
        confirmButton = { TextButton(onClick = { confirmRecycle = false; onDelete() }) {
            Text(if (recycleActions != null) stringResource(R.string.action_delete_now) else stringResource(R.string.detail_move_to_recycle)) } },
        dismissButton = { TextButton(onClick = { confirmRecycle = false }) { Text(stringResource(R.string.action_cancel)) } })
}

/** 时间戳文案。@Composable 以便读取本地化的日期格式。 */
@Composable
private fun formatRecordedAt(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).let {
        stringResource(
            R.string.detail_recorded_at,
            stringResource(R.string.format_date_md, it.monthValue, it.dayOfMonth),
            it.hour, it.minute,
        )
    }

/** 时间戳文案。@Composable 以便读取本地化的日期格式。 */
@Composable
private fun formatUpdatedAt(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).let {
        stringResource(
            R.string.detail_updated_at,
            stringResource(R.string.format_date_md, it.monthValue, it.dayOfMonth),
            it.hour, it.minute,
        )
    }

@Composable
fun FeelingCard(
    feeling: FeelingEntity, repository: ImprintRepository, epoch: Int,
    onUnlock: () -> Unit, onBiometric: () -> Unit,
) {
    var text by remember(feeling, epoch) { mutableStateOf<String?>(null) }
    var needsVerify by remember(feeling, epoch) { mutableStateOf(false) }
    var loaded by remember(feeling, epoch) { mutableStateOf(false) }
    var bioEnabled by remember(feeling, epoch) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(feeling, epoch) {
        val state = repository.security.state()
        val enc = state.feelingMode == "ON"
        val unlocked = repository.security.isFeelingUnlocked()
        bioEnabled = state.feelingBiometricEnvelope != null
        // 加密浏览前先验证；验证前不解密、不把明文放进组合
        needsVerify = enc && !unlocked
        text = if (needsVerify) null
        else runCatching { repository.security.decodeFeeling(feeling) }.getOrNull()
        loaded = true
    }

    if (!loaded) return

    val hasFeel = runCatching { feeling.payload.isNotBlank() }.getOrDefault(true)
    if (!hasFeel && !needsVerify && text.isNullOrBlank()) return

    if (needsVerify) {
        // 已开指纹先弹指纹，否则口令；点按/滑动都触发
        fun beginVerify() {
            scope.launch {
                shake.snapTo(0f)
                shake.animateTo(10f, tween(50))
                shake.animateTo(-10f, tween(100))
                shake.animateTo(8f, tween(100))
                shake.animateTo(-8f, tween(100))
                shake.animateTo(0f, tween(50))
                if (bioEnabled) onBiometric() else onUnlock()
            }
        }
        // 涂层 UI 与未加密一致（绿底+斜纹+星，无文案/无按钮）
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .graphicsLayer { translationX = shake.value }
                .clip(RoundedCornerShape(12.dp))
                .background(Green)
                .pointerInput(feeling.entryId, epoch, bioEnabled) {
                    detectTapGestures(onTap = { beginVerify() })
                }
                .pointerInput(feeling.entryId, epoch, bioEnabled) {
                    detectDragGestures(
                        onDragStart = { beginVerify() },
                        onDrag = { change, _ -> change.consume() },
                    )
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(Green)
                val stripe = Color.White.copy(alpha = 0.14f)
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        stripe,
                        Offset(x, 0f),
                        Offset(x + size.height, size.height),
                        strokeWidth = 3.dp.toPx(),
                    )
                    x += 9.dp.toPx()
                }
                val stars = listOf(
                    Triple(0.16f, 0.35f, 0.11f),
                    Triple(0.44f, 0.70f, 0.08f),
                    Triple(0.70f, 0.28f, 0.13f),
                    Triple(0.88f, 0.60f, 0.07f),
                )
                for ((fx, fy, fr) in stars) {
                    drawSparkle(
                        size.width * fx,
                        size.height * fy,
                        minOf(size.width, size.height) * fr,
                        Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
        return
    }

    text?.let { ScratchFeeling(it, epoch, feeling.entryId) }
}

/**
 * 会话内「已刮开」记忆：同一 epoch（解锁/安全状态）保持，epoch 变化（如重新上锁再解锁）清空。
 * 无「一键全开」按钮。
 */
private object ScratchRevealStore {
    private var epoch: Int = Int.MIN_VALUE
    private val ids = mutableSetOf<String>()

    fun isRevealed(id: String, epoch: Int): Boolean {
        sync(epoch)
        return id in ids
    }

    fun mark(id: String, epoch: Int) {
        sync(epoch)
        ids.add(id)
    }

    private fun sync(epoch: Int) {
        if (this.epoch != epoch) {
            this.epoch = epoch
            ids.clear()
        }
    }
}

/**
 * 真实局部刮卡：
 * - 底层始终是完整感受文字；涂层盖在上面，刮到哪露出哪（无阈值前全局淡出）。
 * - 多笔累计刮开面积 ≥ 80% → 全开，并金色粒子提示。
 * - 已刮开在本 epoch 内保持，直到 lock/epoch 变化。
 */
@Composable
private fun ScratchFeeling(text: String, epoch: Int, feelingId: String) {
    val points = remember(feelingId, epoch) { mutableStateListOf<Offset>() }
    val visited = remember(feelingId, epoch) { mutableSetOf<Pair<Int, Int>>() }
    var size by remember(feelingId, epoch) { mutableStateOf(IntSize.Zero) }
    var phase by remember(feelingId, epoch) {
        mutableStateOf(
            if (ScratchRevealStore.isRevealed(feelingId, epoch)) ScratchPhase.Revealed
            else ScratchPhase.Covered
        )
    }
    var showStars by remember(feelingId, epoch) {
        // 已刮开再进入：不再每次放烟花
        mutableStateOf(false)
    }
    LaunchedEffect(showStars) {
        if (showStars) { delay(1100.milliseconds); showStars = false }
    }
    val cellPx = with(LocalDensity.current) { 16.dp.toPx() }
    val eraseRadius = with(LocalDensity.current) { 15.dp.toPx() }
    val revealed = phase == ScratchPhase.Revealed

    Box(
        Modifier
            .fillMaxWidth()
            .background(PaleGreen, RoundedCornerShape(12.dp))
            .onSizeChanged { size = it },
    ) {
        // 底层：完整文字始终存在，刮开处即可读
        Text(
            text,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            color = Ink,
            lineHeight = 25.sp,
        )
        if (!revealed) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .pointerInput(feelingId, epoch, size) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                points.add(offset)
                                // 多笔累计，不在此清空 visited
                                markCells(offset, cellPx, size, visited)
                            },
                            onDrag = { change, _ ->
                                points.add(change.position)
                                markCells(change.position, cellPx, size, visited)
                                val columns = (size.width / cellPx).toInt() + 1
                                val rows = (size.height / cellPx).toInt() + 1
                                val total = columns * rows
                                if (total > 0 && visited.size.toFloat() / total >= 0.80f) {
                                    phase = ScratchPhase.Revealed
                                    ScratchRevealStore.mark(feelingId, epoch)
                                    showStars = true
                                }
                                change.consume()
                            },
                        )
                    }
            ) {
                // 品牌绿涂层 + 细斜纹 + 白色高光星
                drawRect(Green)
                val stripe = Color.White.copy(alpha = 0.14f)
                val stripeStep = 9.dp.toPx()
                var x = -size.height.toFloat()
                while (x < size.width + size.height) {
                    drawLine(
                        stripe,
                        Offset(x, 0f),
                        Offset(x + size.height, size.height.toFloat()),
                        strokeWidth = 3.dp.toPx(),
                    )
                    x += stripeStep
                }
                val stars = listOf(
                    Triple(0.16f, 0.35f, 0.11f),
                    Triple(0.44f, 0.70f, 0.08f),
                    Triple(0.70f, 0.28f, 0.13f),
                    Triple(0.88f, 0.60f, 0.07f),
                )
                val starColor = Color.White.copy(alpha = 0.35f)
                for ((fx, fy, fr) in stars) {
                    drawSparkle(size.width * fx, size.height * fy, minOf(size.width, size.height) * fr, starColor)
                }
                // 局部刮除：只清笔迹，其余涂层保留
                points.firstOrNull()?.let {
                    drawCircle(Color.Transparent, eraseRadius, it, blendMode = BlendMode.Clear)
                }
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        Color.Transparent, a, b,
                        strokeWidth = eraseRadius * 2,
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Clear,
                    )
                }
            }
        }
        // 全开瞬间：金色为主的粒子提示
        if (showStars) GoldScratchBurst(modifier = Modifier.matchParentSize())
    }
}

private enum class ScratchPhase { Covered, Revealed }

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

/**
 * 全开后的「金闪闪」：沿卡片四周偏下区域闪烁的四角星/短芒。
 * 约 0.75s，较密、整体略靠下。
 */
@Composable
private fun GoldScratchBurst(modifier: Modifier = Modifier) {
    val gold = Color(0xFFF2CE7B)
    val goldBright = Color(0xFFFFF0B0)
    // 相对位置：四角+边中点+加密点缀；y 整体略下沉
    val sparks = remember {
        listOf(
            // 四角（略下移）
            Triple(0.06f, 0.18f, 0f),
            Triple(0.94f, 0.18f, 0.05f),
            Triple(0.94f, 0.92f, 0.10f),
            Triple(0.06f, 0.92f, 0.15f),
            // 四边中点
            Triple(0.50f, 0.12f, 0.08f),
            Triple(0.97f, 0.55f, 0.12f),
            Triple(0.50f, 0.97f, 0.18f),
            Triple(0.03f, 0.55f, 0.22f),
            // 上边加密
            Triple(0.22f, 0.10f, 0.20f),
            Triple(0.38f, 0.08f, 0.26f),
            Triple(0.62f, 0.08f, 0.24f),
            Triple(0.78f, 0.10f, 0.30f),
            // 右边加密
            Triple(0.96f, 0.32f, 0.28f),
            Triple(0.97f, 0.42f, 0.34f),
            Triple(0.96f, 0.68f, 0.32f),
            Triple(0.95f, 0.78f, 0.38f),
            // 下边加密（更密、更靠下）
            Triple(0.18f, 0.96f, 0.36f),
            Triple(0.32f, 0.98f, 0.42f),
            Triple(0.46f, 0.97f, 0.40f),
            Triple(0.60f, 0.98f, 0.46f),
            Triple(0.74f, 0.96f, 0.44f),
            Triple(0.88f, 0.95f, 0.48f),
            // 左边加密
            Triple(0.04f, 0.32f, 0.30f),
            Triple(0.03f, 0.42f, 0.36f),
            Triple(0.04f, 0.68f, 0.34f),
            Triple(0.05f, 0.78f, 0.40f),
            // 内圈贴近边缘的一圈（仍偏下）
            Triple(0.14f, 0.88f, 0.50f),
            Triple(0.40f, 0.90f, 0.54f),
            Triple(0.68f, 0.90f, 0.52f),
            Triple(0.86f, 0.86f, 0.56f),
        )
    }
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 750, easing = LinearOutSlowInEasing),
        )
    }
    val t = anim.value
    Canvas(modifier) {
        // 细金边短暂提亮
        val borderAlpha = kotlin.math.sin(t * kotlin.math.PI).toFloat().coerceIn(0f, 1f) * 0.85f
        if (borderAlpha > 0.01f) {
            val inset = 1.dp.toPx()
            drawRoundRect(
                color = gold.copy(alpha = borderAlpha),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - inset * 2,
                    size.height - inset * 2,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
        val w = size.width
        val h = size.height
        for ((fx, fy, delayFrac) in sparks) {
            // 更短窗口，跟上 0.75s 总时长
            val local = ((t - delayFrac * 0.55f) / 0.28f).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) continue
            val pulse = if (local < 0.4f) local / 0.4f else 1f - (local - 0.4f) / 0.6f
            val alpha = pulse.coerceIn(0f, 1f)
            val cx = fx * w
            val cy = fy * h
            val r = (5f + 9f * pulse).dp.toPx()
            val color = if (pulse > 0.7f) goldBright else gold
            drawSparkle(cx, cy, r, color.copy(alpha = alpha))
            val ray = r * 1.5f
            val rayColor = color.copy(alpha = alpha * 0.7f)
            drawLine(rayColor, Offset(cx - ray, cy), Offset(cx + ray, cy), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(rayColor, Offset(cx, cy - ray), Offset(cx, cy + ray), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

private data class BurstParticle(val color: Color, val size: Float, val dx: Float, val dy: Float)

fun RecycleCountdown(deletedAtMs: Long): String =
    EntryRules.recycleRemainingLabel(deletedAtMs, System.currentTimeMillis())
