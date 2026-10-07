package com.beyondguo.imprints.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource

private val PageBg = Color(0xFFF4F3F0)
private val TabTrack = Color(0xFFFFFFFF)
private val TabActive = Color(0xFFE6F9EF)
private val CellBg = Color(0xFFF7F8F9)
private val DotGreen = Color(0xFF07C160)

private sealed interface TravelTab {
    data object Month : TravelTab
    data object Day : TravelTab
}

/**
 * 「按时间定位」全屏页（对齐设计稿 22/23）：
 * 分段「按月 / 按日」；按月 4×3 月份格 + 年切换；按日月历；绿点=有印迹。
 * 选中后回调目标日期并关闭。
 */
@Composable
fun TimeTravelPage(
    monthsWithEntries: Set<String>,
    daysWithEntries: Set<String>,
    onSelectMonth: (year: Int, month: Int) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf<TravelTab>(TravelTab.Month) }
    var viewYear by remember { mutableStateOf(LocalDate.now().year) }
    var viewMonth by remember { mutableStateOf(YearMonth.now()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBg),
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Ink)
            }
            Text(
                stringResource(R.string.timetravel_title),
                Modifier.weight(1f),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(48.dp))
        }

        // 分段控件
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = TabTrack,
        ) {
            Row {
                SegmentTab(
                    label = stringResource(R.string.timetravel_by_month),
                    selected = tab is TravelTab.Month,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { tab = TravelTab.Month }
                SegmentTab(
                    label = stringResource(R.string.timetravel_by_day),
                    selected = tab is TravelTab.Day,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) { tab = TravelTab.Day }
            }
        }

        when (tab) {
            TravelTab.Month -> MonthPanel(
                year = viewYear,
                monthsWithEntries = monthsWithEntries,
                onYearChange = { viewYear = it },
                onSelect = { month ->
                    onSelectMonth(viewYear, month)
                },
            )
            TravelTab.Day -> DayPanel(
                month = viewMonth,
                daysWithEntries = daysWithEntries,
                onMonthChange = { viewMonth = it },
                onSelect = { day -> onSelectDay(day) },
            )
        }
    }
}

@Composable
private fun SegmentTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) TabActive else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Ink else Quiet,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MonthPanel(
    year: Int,
    monthsWithEntries: Set<String>,
    onYearChange: (Int) -> Unit,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.timetravel_month_title),
            Modifier.fillMaxWidth(),
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.timetravel_month_sub),
            Modifier.fillMaxWidth().padding(top = 4.dp),
            color = Quiet,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("‹", color = Quiet, fontSize = 22.sp, modifier = Modifier
                .clip(CircleShape)
                .clickable { onYearChange(year - 1) }
                .padding(horizontal = 16.dp, vertical = 4.dp))
            Text(stringResource(R.string.timetravel_year, year), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("›", color = Quiet, fontSize = 22.sp, modifier = Modifier
                .clip(CircleShape)
                .clickable { onYearChange(year + 1) }
                .padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(16.dp))

        // 4 列 × 3 行
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            (0 until 12).chunked(4).forEach { rowMonths ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowMonths.forEach { m ->
                        val monthKey = "%04d-%02d".format(year, m + 1)
                        val has = monthsWithEntries.contains(monthKey)
                        MonthCell(
                            label = stringResource(R.string.timetravel_month, m + 1),
                            hasEntries = has,
                            modifier = Modifier.weight(1f).height(52.dp),
                            onClick = { onSelect(m + 1) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(DotGreen)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.timetravel_month_dot), color = Quiet, fontSize = 12.sp)
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.timetravel_day_title),
            Modifier.fillMaxWidth(),
            color = Quiet.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MonthCell(
    label: String,
    hasEntries: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CellBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (hasEntries) DotGreen else Color.Transparent)
            )
        }
    }
}

@Composable
private fun DayPanel(
    month: YearMonth,
    daysWithEntries: Set<String>,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    // ISO：周一=1 … 周日=7 → 列偏移
    val leadBlanks = first.dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val today = LocalDate.now()

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.timetravel_day_sub),
            Modifier.fillMaxWidth(),
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.timetravel_day_tap),
            Modifier.fillMaxWidth().padding(top = 4.dp),
            color = Quiet,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))

        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.editor_prev_month),
                    color = Quiet,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMonthChange(month.minusMonths(1)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.timetravel_month_full, month.year, month.monthValue),
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.editor_next_month),
                    color = Quiet,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMonthChange(month.plusMonths(1)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(
                    w,
                    Modifier.weight(1f),
                    color = Quiet,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val cells = leadBlanks + daysInMonth
        val rows = (cells + 6) / 7
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val cellIndex = r * 7 + c
                        val dayNum = cellIndex - leadBlanks + 1
                        if (dayNum in 1..daysInMonth) {
                            val date = month.atDay(dayNum)
                            val key = date.toString()
                            DayCell(
                                day = dayNum,
                                hasEntries = daysWithEntries.contains(key),
                                isToday = date == today,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                onClick = { onSelect(date) },
                            )
                        } else {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(DotGreen))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.timetravel_day_dot), color = Quiet, fontSize = 12.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.timetravel_day_heading),
            Modifier.fillMaxWidth(),
            color = Quiet.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DayCell(
    day: Int,
    hasEntries: Boolean,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isToday -> TabActive
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.calendar_day, day),
                color = if (isToday) DotGreen else Ink,
                fontSize = 14.sp,
                fontWeight = if (isToday || hasEntries) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (hasEntries) DotGreen else Color.Transparent)
            )
        }
    }
}
