package com.beyondguo.imprints.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.imprints.R
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.LetterListRow
import com.beyondguo.imprints.data.LetterRepository
import com.beyondguo.imprints.data.PeriodKind
import com.beyondguo.imprints.data.ReviewCandidate
import com.beyondguo.imprints.data.ReviewDetail
import com.beyondguo.imprints.data.ReviewDraft
import com.beyondguo.imprints.data.ReviewEntity
import com.beyondguo.imprints.data.ReviewRepository
import com.beyondguo.imprints.data.SourceKey
import com.beyondguo.imprints.data.SourceKind
import com.beyondguo.imprints.data.SourceVisibility
import com.beyondguo.imprints.data.reviewDraftFromJson
import com.beyondguo.imprints.review.DateRange
import com.beyondguo.imprints.review.ReviewPeriods
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

// ---------- 回顾一级页 ----------

@Composable
fun ReviewHomePage(
    reviewRepository: ReviewRepository,
    coreRepository: ImprintRepository,
    nowDate: LocalDate,
    onStartWizard: (String, String, String, List<String>) -> Unit,
    onOpenReview: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    onDeleteDraft: (String) -> Unit,
    report: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reviews by reviewRepository.activeReviews.collectAsState(initial = emptyList())
    val drafts by reviewRepository.reviewDrafts.collectAsState(initial = emptyList())
    val tagList by coreRepository.selectableTags.collectAsState(initial = emptyList())
    var kind by remember { mutableStateOf(PeriodKind.MONTH.name) }
    var rangeShift by remember { mutableStateOf(0) }
    var weekEntryCount by remember { mutableStateOf(0) }
    var customStart by remember { mutableStateOf(nowDate.minusMonths(1).toString()) }
    var customEnd by remember { mutableStateOf(nowDate.toString()) }
    // 自选范围用日期选择器，不再让用户手敲 "2026-09-22"（格式错一个字符就整段回退）
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    LaunchedEffect(nowDate) {
        runCatching { reviewRepository.candidates(ReviewPeriods.weekOf(nowDate), emptySet()) }
            .onSuccess { weekEntryCount = it.size }
    }

    val range = remember(kind, rangeShift, customStart, customEnd, nowDate) {
        if (kind == PeriodKind.CUSTOM.name) {
            // 自选：开始不晚于结束、截止不晚于今天；不合法时退回到最近一个自然月
            runCatching {
                val start = LocalDate.parse(customStart)
                val end = LocalDate.parse(customEnd)
                ReviewPeriods.validateCustom(start, end, nowDate)
                DateRange(start, end)
            }.getOrNull() ?: resolveRange(PeriodKind.MONTH.name, 0, nowDate)
        } else {
            resolveRange(kind, rangeShift, nowDate)
        }
    }
    val isCurrent = range.endInclusive >= nowDate

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.review_title), Modifier.weight(1f), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }

        if (weekEntryCount > 0) {
            OutlinePanel(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.review_this_week, weekEntryCount),
                        Modifier.weight(1f), color = Ink, fontSize = 14.sp,
                    )
                    TextButton(onClick = {
                        val week = ReviewPeriods.weekOf(nowDate)
                        onStartWizard(PeriodKind.WEEK.name, week.start.toString(), week.endInclusive.toString(), emptyList())
                    }) { Text(stringResource(R.string.review_this_week_action), color = Green) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PeriodKind.WEEK.name to R.string.review_kind_week,
                PeriodKind.MONTH.name to R.string.review_kind_month,
                PeriodKind.YEAR.name to R.string.review_kind_year,
                PeriodKind.CUSTOM.name to R.string.review_kind_custom,
            ).forEach { (value, label) ->
                TagChip(text = stringResource(label), selected = kind == value, onClick = {
                    kind = value
                    rangeShift = 0
                })
            }
        }

        if (kind != PeriodKind.CUSTOM.name) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { rangeShift-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.action_back), tint = Quiet) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rangeLabel(range), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (isCurrent) Text(stringResource(R.string.review_as_of_today), color = Quiet, fontSize = 11.sp)
                }
                IconButton(onClick = { if (rangeShift < 0) rangeShift++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_continue), tint = Quiet) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReviewDateField(
                    label = stringResource(R.string.review_step_range),
                    value = customStart,
                    onClick = { showStartPicker = true },
                    modifier = Modifier.weight(1f),
                )
                ReviewDateField(
                    label = stringResource(R.string.review_as_of_today),
                    value = customEnd,
                    onClick = { showEndPicker = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showStartPicker) ReviewDatePickerDialog(
            initial = runCatching { LocalDate.parse(customStart) }.getOrDefault(nowDate),
            onDismiss = { showStartPicker = false },
            onPick = { customStart = it.toString(); showStartPicker = false },
        )
        if (showEndPicker) ReviewDatePickerDialog(
            initial = runCatching { LocalDate.parse(customEnd) }.getOrDefault(nowDate),
            onDismiss = { showEndPicker = false },
            onPick = { customEnd = it.toString(); showEndPicker = false },
        )

        Button(
            onClick = { onStartWizard(kind, range.start.toString(), range.endInclusive.toString(), emptyList()) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.review_create)) }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (drafts.isNotEmpty()) {
                item { Text(stringResource(R.string.mail_draft_badge, drafts.size), color = Quiet, fontSize = 13.sp) }
                items(drafts, key = { "draft-" + it.id }) { draft ->
                    OutlinePanel(Modifier.fillMaxWidth().clickable { onOpenDraft(draft.id) }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                runCatching { reviewDraftFromJson(draft.id, draft.payload) }.getOrNull()?.title
                                    ?.takeIf { it.isNotBlank() } ?: stringResource(R.string.review_title),
                                Modifier.weight(1f), color = Ink, fontSize = 15.sp, maxLines = 1,
                            )
                            IconButton(onClick = { onDeleteDraft(draft.id) }, modifier = Modifier.size(32.dp)) {
                                Text("×", color = Quiet, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.review_mine), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (reviews.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.review_empty), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.review_empty_sub), color = Quiet, fontSize = 13.sp)
                    }
                }
            }
            items(reviews, key = { "review-" + it.id }) { review ->
                OutlinePanel(Modifier.fillMaxWidth().clickable { onOpenReview(review.id) }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(rangeLabelOf(review), color = Quiet, fontSize = 12.sp)
                        Text(
                            review.title.ifBlank { defaultTitleOf(review) },
                            color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private fun resolveRange(kind: String, shift: Int, today: LocalDate): DateRange {
    val base = when (kind) {
        PeriodKind.WEEK.name -> ReviewPeriods.lastCompletedWeek(today)
        PeriodKind.YEAR.name -> ReviewPeriods.lastCompletedYear(today)
        else -> ReviewPeriods.lastCompletedMonth(today)
    }
    return when (kind) {
        PeriodKind.WEEK.name -> DateRange(base.start.plusDays(7L * shift), base.endInclusive.plusDays(7L * shift))
        PeriodKind.YEAR.name -> DateRange(base.start.plusYears(shift.toLong()), base.endInclusive.plusYears(shift.toLong()))
        else -> {
            val start = base.start.plusMonths(shift.toLong())
            DateRange(start, java.time.YearMonth.from(start).atEndOfMonth())
        }
    }
}

@Composable
private fun rangeLabel(range: DateRange): String = stringResource(
    R.string.format_date_ymd, range.start.year, range.start.monthValue, range.start.dayOfMonth,
) + " ~ " + stringResource(R.string.format_date_md, range.endInclusive.monthValue, range.endInclusive.dayOfMonth)

@Composable
private fun rangeLabelOf(review: ReviewEntity): String {
    val start = runCatching { LocalDate.parse(review.startLocalDate) }.getOrNull() ?: return review.startLocalDate
    val end = runCatching { LocalDate.parse(review.endLocalDateInclusive) }.getOrNull() ?: return review.endLocalDateInclusive
    return stringResource(R.string.format_date_ymd, start.year, start.monthValue, start.dayOfMonth) +
        " ~ " + stringResource(R.string.format_date_md, end.monthValue, end.dayOfMonth)
}

@Composable
private fun defaultTitleOf(review: ReviewEntity): String {
    val start = runCatching { LocalDate.parse(review.startLocalDate) }.getOrNull() ?: return review.startLocalDate
    return when (review.periodKind) {
        PeriodKind.YEAR.name -> stringResource(R.string.timetravel_month_full, start.year, 1).substringBefore("年") + " 年回顾"
        PeriodKind.MONTH.name -> stringResource(R.string.timetravel_month_full, start.year, start.monthValue) + "回顾"
        PeriodKind.WEEK.name -> stringResource(R.string.format_date_md, start.monthValue, start.dayOfMonth) + "那周回顾"
        else -> rangeLabelOf(review) + "回顾"
    }
}

// ---------- 回顾向导 ----------

private data class PickState(
    val included: Boolean = true,
    val featured: Boolean = false,
)

@Composable
fun ReviewWizardPage(
    reviewRepository: ReviewRepository,
    letterRepository: LetterRepository,
    coreRepository: ImprintRepository,
    initialDraft: ReviewDraft?,
    draftId: String,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var periodKind by remember { mutableStateOf(initialDraft?.periodKind ?: PeriodKind.MONTH.name) }
    var startText by remember { mutableStateOf(initialDraft?.startLocalDate ?: "") }
    var endText by remember { mutableStateOf(initialDraft?.endLocalDateInclusive ?: "") }
    var selectedTagIds by remember { mutableStateOf(initialDraft?.selectedTagIds?.toSet() ?: emptySet()) }
    var title by remember { mutableStateOf(initialDraft?.title ?: "") }
    var userBody by remember { mutableStateOf(initialDraft?.userBody ?: "") }
    var closingText by remember { mutableStateOf(initialDraft?.closingText ?: "") }
    var coverKey by remember { mutableStateOf(initialDraft?.coverSourceKey) }
    var picks by remember { mutableStateOf(emptyMap<String, PickState>()) }
    var featuredOrder by remember { mutableStateOf(initialDraft?.featuredKeys ?: emptyList()) }
    var candidates by remember { mutableStateOf(emptyList<ReviewCandidate>()) }
    var letterSources by remember { mutableStateOf(emptyList<LetterListRow>()) }
    val tagList by coreRepository.selectableTags.collectAsState(initial = emptyList())

    val range = remember(startText, endText) {
        runCatching { DateRange(LocalDate.parse(startText), LocalDate.parse(endText)) }.getOrNull()
    }

    LaunchedEffect(range, selectedTagIds) {
        val r = range ?: return@LaunchedEffect
        runCatching { reviewRepository.candidates(r, selectedTagIds) }
            .onSuccess { list ->
                candidates = list
                val restored = picks
                picks = list.associate { candidate ->
                    val key = candidate.key.toString()
                    key to (restored[key] ?: PickState(included = true, featured = key in featuredOrder))
                }
            }
            .onFailure(report)
        runCatching { reviewRepository.addableLetters() }.onSuccess { letterSources = it }
    }

    fun saveDraft() {
        val r = range ?: return
        scope.launch {
            runCatching {
                reviewRepository.saveDraft(
                    draftId,
                    ReviewDraft(
                        draftId = draftId,
                        reviewId = initialDraft?.reviewId,
                        periodKind = periodKind,
                        startLocalDate = r.start.toString(),
                        endLocalDateInclusive = r.endInclusive.toString(),
                        selectedTagIds = selectedTagIds.toList(),
                        title = title,
                        userBody = userBody,
                        closingText = closingText,
                        coverSourceKey = coverKey,
                        includedKeys = picks.filterValues { it.included }.keys.toList(),
                        featuredKeys = featuredOrder,
                    ),
                )
            }.onFailure(report)
        }
    }

    fun requestExit() {
        saveDraft()
        onCancel()
    }
    BackHandler { requestExit() }

    val stepTitles = listOf(
        stringResource(R.string.review_step_range),
        stringResource(R.string.review_step_pick),
        stringResource(R.string.review_step_write),
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = {
                if (step == 0) requestExit() else { saveDraft(); step-- }
            }, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(if (step == 0) stringResource(R.string.action_cancel) else stringResource(R.string.action_back), color = Ink, fontSize = 16.sp)
            }
            Text(
                stepTitles[step],
                Modifier.align(Alignment.Center),
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = {
                    when (step) {
                        0 -> step = 1
                        1 -> step = 2
                        else -> {
                            val r = range
                            if (r == null) {
                                report(IllegalStateException("请选择时间范围"))
                            } else {
                                val included = picks.filterValues { it.included }.keys
                                val noMaterial = included.isEmpty()
                                if (noMaterial && userBody.isBlank()) {
                                    report(IllegalStateException("没有素材时请填写总结正文"))
                                } else {
                                    scope.launch {
                                        val draft = ReviewDraft(
                                            draftId = draftId,
                                            reviewId = initialDraft?.reviewId,
                                            periodKind = periodKind,
                                            startLocalDate = r.start.toString(),
                                            endLocalDateInclusive = r.endInclusive.toString(),
                                            selectedTagIds = selectedTagIds.toList(),
                                            title = title,
                                            userBody = userBody,
                                            closingText = closingText,
                                            coverSourceKey = coverKey?.takeIf { key -> included.contains(key) },
                                            includedKeys = included.toList(),
                                            featuredKeys = featuredOrder.filter { it in included },
                                        )
                                        runCatching { reviewRepository.save(draft) }
                                            .onSuccess(onSaved)
                                            .onFailure(report)
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(if (step == 2) stringResource(R.string.review_save) else stringResource(R.string.action_continue), color = Green, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            when (step) {
                0 -> {
                    item {
                        Text(
                            (range?.let { rangeLabel(it) } ?: "") + if (range?.endInclusive ?: LocalDate.MIN >= LocalDate.now()) " · " + stringResource(R.string.review_as_of_today) else "",
                            color = Ink, fontSize = 15.sp,
                        )
                    }
                    item { Text(stringResource(R.string.review_step_range), color = Quiet, fontSize = 13.sp) }
                    item {
                        FlowRowTags(
                            tags = tagList.map { it.id to it.name },
                            selected = selectedTagIds,
                            onToggle = { id ->
                                selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
                            },
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.review_included_count, candidates.size),
                            color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
                1 -> {
                    item { Text(stringResource(R.string.review_pick_hint), color = Quiet, fontSize = 12.sp, lineHeight = 18.sp) }
                    item {
                        Text(
                            stringResource(R.string.review_included_count, picks.count { it.value.included }) + " · " +
                                stringResource(R.string.review_featured_count, picks.count { it.value.featured }),
                            color = Green, fontSize = 13.sp,
                        )
                    }
                    items(candidates, key = { it.entry.id }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            state = picks[candidate.key.toString()] ?: PickState(),
                            onToggleIncluded = {
                                val key = candidate.key.toString()
                                val current = picks[key] ?: PickState()
                                val next = current.copy(included = !current.included, featured = if (!current.included) current.featured else false)
                                picks = picks + (key to next)
                                if (!next.included) featuredOrder = featuredOrder - key
                            },
                            onToggleFeatured = {
                                val key = candidate.key.toString()
                                val current = picks[key] ?: PickState()
                                if (!current.included) return@CandidateRow
                                val next = current.copy(featured = !current.featured)
                                picks = picks + (key to next)
                                featuredOrder = if (next.featured) featuredOrder + key else featuredOrder - key
                            },
                            onMakeCover = {
                                val key = candidate.key.toString()
                                val current = picks[key] ?: PickState()
                                if (current.included) coverKey = if (coverKey == key) null else key
                            },
                            isCover = coverKey == candidate.key.toString(),
                            onMove = { up ->
                                val key = candidate.key.toString()
                                val index = featuredOrder.indexOf(key)
                                if (index >= 0) {
                                    val target = if (up) index - 1 else index + 1
                                    if (target in featuredOrder.indices) {
                                        val mutable = featuredOrder.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(target, item)
                                        featuredOrder = mutable
                                    }
                                }
                            },
                        )
                    }
                    if (letterSources.isNotEmpty()) {
                        item { Text(stringResource(R.string.review_add_letter), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        items(letterSources, key = { "letter-" + it.entryId }) { row ->
                            val key = SourceKey(SourceKind.LETTER.name, row.entryId).toString()
                            val state = picks[key]
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val current = picks[key] ?: PickState(included = false)
                                    val next = current.copy(included = !current.included)
                                    picks = picks + (key to next)
                                    if (!next.included) featuredOrder = featuredOrder - key
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (state?.included == true) "☑ " else "☐ ",
                                    color = Green, fontSize = 18.sp,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.review_source_letter) + " · " + stringResource(R.string.mail_letter_default_title), color = Ink, fontSize = 15.sp)
                                    Text(formatLetterDate(row), color = Quiet, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                else -> {
                    item {
                        Text(stringResource(R.string.review_label_overview), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    item {
                        val includedCandidates = candidates.filter { picks[it.key.toString()]?.included == true }
                        val days = includedCandidates.map { it.entry.eventLocalMinute.take(10) }.toSet().size
                        val visuals = includedCandidates.sumOf { it.visualCount }
                        val letterCount = letterSources.count { picks[SourceKey(SourceKind.LETTER.name, it.entryId).toString()]?.included == true }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.review_stat_entries) + "：" + includedCandidates.size, color = Ink, fontSize = 14.sp)
                            Text(stringResource(R.string.review_stat_days) + "：" + days, color = Ink, fontSize = 14.sp)
                            Text(stringResource(R.string.review_stat_visuals) + "：" + visuals, color = Ink, fontSize = 14.sp)
                            if (letterCount > 0) Text(stringResource(R.string.review_stat_letters) + "：" + letterCount, color = Ink, fontSize = 14.sp)
                        }
                    }
                    item {
                        OutlinedTextField(
                            title, { title = it },
                            placeholder = { Text(stringResource(R.string.review_title_hint), color = Quiet) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green),
                        )
                    }
                    item {
                        TextField(
                            userBody, { userBody = it },
                            placeholder = { Text(stringResource(R.string.review_prompt), color = Quiet) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 6,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 27.sp, color = Ink),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    }
                    item {
                        OutlinedTextField(
                            closingText, { closingText = it },
                            placeholder = { Text(stringResource(R.string.review_closing), color = Quiet) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, focusedBorderColor = Green),
                        )
                    }
                    item {
                        Text(stringResource(R.string.review_label_snippets), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    items(featuredOrder.filter { picks[it]?.featured == true }, key = { "f-$it" }) { key ->
                        val candidate = candidates.firstOrNull { it.key.toString() == key }
                        OutlinePanel(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate?.entry?.eventLocalMinute?.take(10) ?: key, color = Quiet, fontSize = 12.sp)
                                Text(candidate?.entry?.body?.take(120) ?: stringResource(R.string.review_source_letter), color = Ink, fontSize = 14.sp, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatLetterDate(row: LetterListRow): String {
    val date = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(row.createdAtMs), java.time.ZoneId.systemDefault())
    return stringResource(R.string.format_date_ymd, date.year, date.monthValue, date.dayOfMonth)
}

@Composable
private fun FlowRowTags(tags: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TagChip(text = stringResource(R.string.tag_filter_all), selected = selected.isEmpty(), onClick = { selected.forEach(onToggle) })
        tags.forEach { (id, name) ->
            TagChip(text = name, selected = id in selected, onClick = { onToggle(id) })
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ReviewCandidate,
    state: PickState,
    onToggleIncluded: () -> Unit,
    onToggleFeatured: () -> Unit,
    onMakeCover: () -> Unit,
    isCover: Boolean,
    onMove: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggleIncluded).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (state.included) "☑ " else "☐ ", color = Green, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(candidate.entry.eventLocalMinute.replace('T', ' '), color = Quiet, fontSize = 12.sp)
            Text(candidate.entry.body.ifBlank { stringResource(R.string.entry_media_only) }, color = Ink, fontSize = 14.sp, maxLines = 2)
        }
        IconButton(onClick = onToggleFeatured, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Star, null, tint = if (state.featured) Green else Line)
        }
        if (state.featured) {
            IconButton(onClick = { onMove(true) }, modifier = Modifier.size(32.dp)) { Text("↑", color = Quiet, fontSize = 14.sp) }
            IconButton(onClick = { onMove(false) }, modifier = Modifier.size(32.dp)) { Text("↓", color = Quiet, fontSize = 14.sp) }
        }
        TextButton(onClick = onMakeCover) {
            Text(
                if (isCover) stringResource(R.string.review_cover_pick) + " ✓" else stringResource(R.string.review_cover_pick),
                color = if (isCover) Green else Quiet, fontSize = 12.sp,
            )
        }
    }
}

// ---------- 总结详情 ----------

@Composable
fun ReviewDetailPage(
    reviewRepository: ReviewRepository,
    coreRepository: ImprintRepository,
    letterRepository: LetterRepository,
    reviewId: String,
    onOpenEntry: (String) -> Unit,
    onWriteLetter: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember(reviewId) { mutableStateOf<ReviewDetail?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAddLetter by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var updatePreview by remember { mutableStateOf<com.beyondguo.imprints.data.ReviewUpdatePreview?>(null) }
    var addSelection by remember { mutableStateOf(emptySet<String>()) }
    var refreshSelection by remember { mutableStateOf(emptySet<String>()) }
    var removeSelection by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(reviewId) {
        runCatching { reviewRepository.detail(reviewId) }.onSuccess { detail = it }.onFailure(report)
        // 「来源记录有变化」：进入总结时对比来源指纹，变化只提示、不自动改写
        runCatching {
            val review = reviewRepository.review(reviewId)
            if (review == null) {
                null
            } else {
                val range = DateRange(LocalDate.parse(review.startLocalDate), LocalDate.parse(review.endLocalDateInclusive))
                reviewRepository.updatePreview(reviewId, range, review.selectedTagIds.split(',').filter(String::isNotBlank).toSet())
            }
        }.onSuccess { preview ->
            updatePreview = preview?.takeIf { it.added.isNotEmpty() || it.changed.isNotEmpty() || it.removed.isNotEmpty() }
        }
    }

    val current = detail
    if (current == null) {
        Text(stringResource(R.string.state_loading), Modifier.padding(20.dp))
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rangeLabelOf(current.review), color = Quiet, fontSize = 13.sp)
                Text(
                    current.review.title.ifBlank { defaultTitleOf(current.review) },
                    color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        updatePreview?.let { preview ->
            item {
                OutlinePanel(Modifier.fillMaxWidth().clickable {
                    addSelection = preview.added.map { it.key.toString() }.toSet()
                    refreshSelection = preview.changed.map { it.first.toString() }.toSet()
                    removeSelection = emptySet()
                    showUpdate = true
                }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.review_changed), Modifier.weight(1f), color = Green, fontSize = 14.sp)
                        Text(stringResource(R.string.review_update), color = Green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.review_label_overview), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        item {
            OutlinePanel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.review_stat_entries) + "：" + current.stats.entryCount, color = Ink, fontSize = 14.sp)
                    Text(stringResource(R.string.review_stat_days) + "：" + current.stats.recordDays, color = Ink, fontSize = 14.sp)
                    Text(stringResource(R.string.review_stat_visuals) + "：" + current.stats.visualCount, color = Ink, fontSize = 14.sp)
                    if (current.stats.letterCount > 0) {
                        Text(stringResource(R.string.review_stat_letters) + "：" + current.stats.letterCount, color = Ink, fontSize = 14.sp)
                    }
                    if (current.stats.tagCounts.isNotEmpty()) {
                        val names = current.review.tagNameSnapshot.split(',')
                        val ids = current.review.selectedTagIds.split(',')
                        Text(
                            stringResource(R.string.review_stat_tags) + "：" + current.stats.tagCounts.joinToString("、") { (id, count) ->
                                val index = ids.indexOf(id)
                                (if (index in names.indices) names[index] else id) + " $count"
                            },
                            color = Ink, fontSize = 14.sp,
                        )
                    }
                }
            }
        }
        item { Text(stringResource(R.string.review_label_snippets), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        items(current.sources.filter { it.featured && it.visibility == SourceVisibility.VISIBLE.name }, key = { "s-${it.sourceKind}-${it.sourceId}" }) { source ->
            OutlinePanel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.eventDate, color = Quiet, fontSize = 12.sp)
                    if (source.sourceKind == SourceKind.LETTER.name) {
                        Text(stringResource(R.string.review_source_letter), color = Green, fontSize = 12.sp)
                    }
                    Text(source.bodySnapshot, color = Ink, fontSize = 15.sp, lineHeight = 23.sp, maxLines = 6)
                    TextButton(onClick = {
                        if (source.sourceKind == SourceKind.ENTRY.name) onOpenEntry(source.sourceId)
                        else onOpenEntry(source.sourceId)
                    }) { Text(stringResource(R.string.review_source_entry), color = Green, fontSize = 13.sp) }
                }
            }
        }
        items(current.sources.filter { it.visibility != SourceVisibility.VISIBLE.name }, key = { "h-${it.sourceKind}-${it.sourceId}" }) { source ->
            OutlinePanel(Modifier.fillMaxWidth()) {
                Text(
                    if (source.visibility == SourceVisibility.RECYCLED.name) stringResource(R.string.review_source_recycled)
                    else stringResource(R.string.review_source_purged),
                    Modifier.padding(14.dp), color = Quiet, fontSize = 13.sp,
                )
            }
        }
        if (current.review.userBody.isNotBlank()) {
            item { Text(stringResource(R.string.review_label_summary), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            item { Text(current.review.userBody, color = Ink, fontSize = 16.sp, lineHeight = 26.sp) }
        }
        if (current.review.closingText.isNotBlank()) {
            item { Text(current.review.closingText, color = Green, fontSize = 15.sp, lineHeight = 24.sp) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton2(stringResource(R.string.action_edit)) { onEdit() }
                OutlinedButton2(stringResource(R.string.review_add_letter)) { showAddLetter = true }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton2(stringResource(R.string.review_write_letter)) { onWriteLetter(current.review.closingText) }
                OutlinedButton2(stringResource(R.string.action_delete_forever), danger = true) { confirmDelete = true }
            }
        }
        item { Text(stringResource(R.string.review_delete_hint), color = Quiet, fontSize = 12.sp) }
    }

    if (showUpdate) updatePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { showUpdate = false },
            title = { Text(stringResource(R.string.review_update)) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (preview.added.isNotEmpty()) Text(stringResource(R.string.review_update_added), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    preview.added.forEach { candidate ->
                        val key = candidate.key.toString()
                        Row(Modifier.fillMaxWidth().clickable {
                            addSelection = if (key in addSelection) addSelection - key else addSelection + key
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (key in addSelection) "☑ " else "☐ ", color = Green, fontSize = 16.sp)
                            Text(candidate.entry.body.ifBlank { candidate.entry.eventLocalMinute.take(10) }, color = Ink, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    if (preview.changed.isNotEmpty()) Text(stringResource(R.string.review_update_changed), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    preview.changed.forEach { (source, candidate) ->
                        val key = source.toString()
                        Row(Modifier.fillMaxWidth().clickable {
                            refreshSelection = if (key in refreshSelection) refreshSelection - key else refreshSelection + key
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (key in refreshSelection) "☑ " else "☐ ", color = Green, fontSize = 16.sp)
                            Text(candidate.entry.body.ifBlank { candidate.entry.eventLocalMinute.take(10) }, color = Ink, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    if (preview.removed.isNotEmpty()) Text(stringResource(R.string.review_update_removed), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    preview.removed.forEach { source ->
                        val key = source.toString()
                        Row(Modifier.fillMaxWidth().clickable {
                            removeSelection = if (key in removeSelection) removeSelection - key else removeSelection + key
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (key in removeSelection) "☑ " else "☐ ", color = Danger, fontSize = 16.sp)
                            Text(source.bodySnapshot.ifBlank { source.eventDate }, color = Quiet, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdate = false
                    scope.launch {
                        val review = reviewRepository.review(reviewId) ?: return@launch
                        val range = DateRange(
                            LocalDate.parse(review.startLocalDate),
                            LocalDate.parse(review.endLocalDateInclusive),
                        )
                        runCatching {
                            reviewRepository.applyUpdate(reviewId, preview, addSelection, refreshSelection, removeSelection, range)
                        }.onSuccess {
                            detail = reviewRepository.detail(reviewId)
                            updatePreview = null
                        }.onFailure(report)
                    }
                }) { Text(stringResource(R.string.review_update_apply)) }
            },
            dismissButton = { TextButton(onClick = { showUpdate = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.action_delete_forever)) },
        text = { Text(stringResource(R.string.review_delete_hint)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    runCatching { reviewRepository.moveToRecycle(reviewId) }
                        .onSuccess { onDelete() }.onFailure(report)
                }
            }) { Text(stringResource(R.string.recycle_type_review) + stringResource(R.string.envelope_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (showAddLetter) {
        val scope2 = rememberCoroutineScope()
        var addable by remember { mutableStateOf(emptyList<LetterListRow>()) }
        LaunchedEffect(Unit) { runCatching { reviewRepository.addableLetters() }.onSuccess { addable = it } }
        AlertDialog(
            onDismissRequest = { showAddLetter = false },
            title = { Text(stringResource(R.string.review_add_letter)) },
            text = {
                Column {
                    if (addable.isEmpty()) Text(stringResource(R.string.mail_empty_delivered_sub), color = Quiet, fontSize = 13.sp)
                    addable.forEach { row ->
                        TextButton(onClick = {
                            scope2.launch {
                                runCatching { reviewRepository.addLetterSource(reviewId, row.entryId) }
                                    .onSuccess {
                                        showAddLetter = false
                                        scope.launch { runCatching { reviewRepository.detail(reviewId) }.onSuccess { detail = it } }
                                    }
                                    .onFailure(report)
                            }
                        }) { Text(stringResource(R.string.mail_letter_default_title) + " · " + formatLetterDate(row)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddLetter = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 回顾「自选」范围的日期字段：只读展示，点击弹日期选择器。
 *
 * 不用输入框的原因：手敲 "2026-09-22" 只要格式差一个字符，range 解析就失败、整段静默回退到
 * 最近一个自然月，用户完全不知道自己填错了；手机上弹数字键盘选日期本身也别扭。
 */
@Composable
private fun ReviewDateField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Quiet, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Text(value, color = Ink, fontSize = 15.sp)
        }
    }
}

/**
 * Material3 日期选择器。
 * 注意 DatePicker 的毫秒值以 **UTC 零点** 为基准，进出一律按 UTC 换算，
 * 否则东八区会整体偏一天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewDatePickerDialog(
    initial: java.time.LocalDate,
    onDismiss: () -> Unit,
    onPick: (java.time.LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onPick(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate())
                }
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}
