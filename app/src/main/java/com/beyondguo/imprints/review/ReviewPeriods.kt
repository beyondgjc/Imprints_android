package com.beyondguo.imprints.review

import java.time.LocalDate
import java.time.YearMonth

/** 含当日的日期区间（展示给用户的起止日期都含当天）。 */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    val days: Long
        get() = endInclusive.toEpochDay() - start.toEpochDay() + 1

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    init {
        require(!start.isAfter(endInclusive)) { "开始日期不得晚于结束日期" }
    }
}

/**
 * 回顾时间范围（RV-02）的纯规则。
 *
 * - 周起点固定为**周一**，不随设备语言变化；
 * - 「已结束」= 结束日早于今天；允许选当前周期，此时截止到今天（UI 标「截至今天」）；
 * - 内部按 ISO 日期字符串（yyyy-MM-dd）比较与归类，保留事件当时的本地日期，
 *   不按当前设备时区重新折算；**不得**把 createdAtMs 当筛选字段。
 */
object ReviewPeriods {
    const val WEEK = "WEEK"
    const val MONTH = "MONTH"
    const val YEAR = "YEAR"
    const val CUSTOM = "CUSTOM"

    fun allKinds(): List<String> = listOf(WEEK, MONTH, YEAR, CUSTOM)

    /** 周一为固定周起点（ISO-8601）。 */
    fun weekStart(date: LocalDate): LocalDate = date.with(java.time.DayOfWeek.MONDAY)

    fun weekOf(date: LocalDate): DateRange = DateRange(weekStart(date), weekStart(date).plusDays(6))

    fun monthOf(date: LocalDate): DateRange {
        val month = YearMonth.from(date)
        return DateRange(month.atDay(1), month.atEndOfMonth())
    }

    fun yearOf(date: LocalDate): DateRange = DateRange(date.withDayOfYear(1), date.withDayOfYear(date.lengthOfYear()))

    /** 最近一个**已结束**的自然周（周一~周日）。 */
    fun lastCompletedWeek(today: LocalDate): DateRange = weekOf(today).shift(-7)

    /** 最近一个**已结束**的自然月。 */
    fun lastCompletedMonth(today: LocalDate): DateRange = monthOf(today).shiftMonths(-1)

    /** 最近一个**已结束**的自然年。 */
    fun lastCompletedYear(today: LocalDate): DateRange = yearOf(today.minusYears(1))

    fun current(kind: String, today: LocalDate): DateRange = when (kind) {
        WEEK -> weekOf(today)
        MONTH -> monthOf(today)
        YEAR -> yearOf(today)
        else -> throw IllegalArgumentException("自选范围没有「当前周期」")
    }

    /** 向导默认范围：最近一个已结束的周期。 */
    fun defaultRange(kind: String, today: LocalDate): DateRange = when (kind) {
        WEEK -> lastCompletedWeek(today)
        MONTH -> lastCompletedMonth(today)
        YEAR -> lastCompletedYear(today)
        else -> throw IllegalArgumentException("自选范围由用户输入")
    }

    fun isCompleted(range: DateRange, today: LocalDate): Boolean = range.endInclusive.isBefore(today)

    /** 自选范围校验：开始不晚于结束，截止不晚于今天，无固定跨度上限。 */
    fun validateCustom(start: LocalDate, end: LocalDate, today: LocalDate) {
        require(!start.isAfter(end)) { "开始日期不得晚于结束日期" }
        require(!end.isAfter(today)) { "截止日期不得晚于今天" }
    }

    /** 统计口径的归类键：事件日期（ISO 字符串）。 */
    fun dateKey(value: String): String = value.take(10)

    fun parseDate(value: String): LocalDate = LocalDate.parse(value.take(10))

    fun enumerateDays(range: DateRange): List<LocalDate> =
        (0 until range.days).map { range.start.plusDays(it) }

    /** 月回顾按周组织片段：把区间切成自然周片段（首尾可能不足一周）。 */
    fun weeksOf(range: DateRange): List<DateRange> {
        val result = mutableListOf<DateRange>()
        var cursor = weekStart(range.start)
        while (!cursor.isAfter(range.endInclusive)) {
            val end = minOf(cursor.plusDays(6), range.endInclusive)
            result += DateRange(maxOf(cursor, range.start), end)
            cursor = cursor.plusDays(7)
        }
        return result
    }

    /** 年回顾按月组织片段。 */
    fun monthsOf(range: DateRange): List<YearMonth> {
        val result = mutableListOf<YearMonth>()
        var cursor = YearMonth.from(range.start)
        val last = YearMonth.from(range.endInclusive)
        while (!cursor.isAfter(last)) {
            result += cursor
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    /**
     * 首页回顾邀请的目标周期（RV-01）：最近一个**已结束且有普通印迹**的周期，
     * 同时存在时优先年、月、周。返回 null = 没有可邀请的周期。
     */
    fun invitationRange(today: LocalDate, eventDates: Collection<String>, closed: (String, DateRange) -> Boolean): Pair<String, DateRange>? {
        val dates = eventDates.map { parseDate(it) }.toSet()
        for (kind in listOf(YEAR, MONTH, WEEK)) {
            val range = defaultRange(kind, today)
            if (dates.none { range.contains(it) }) continue
            if (closed(kind, range)) continue
            return kind to range
        }
        return null
    }

    /** 默认标题（RV-01）：「YYYY 年 M 月回顾」等。 */
    fun defaultTitle(kind: String, range: DateRange): String = when (kind) {
        WEEK -> "${range.start.year} 年 ${range.start.monthValue} 月第 ${weekIndexInMonth(range.start)} 周回顾"
        MONTH -> "${range.start.year} 年 ${range.start.monthValue} 月回顾"
        YEAR -> "${range.start.year} 年回顾"
        else -> "${range.start} ~ ${range.endInclusive} 回顾"
    }

    /** 该周是本月第几个自然周（按周一归属），仅用于默认标题文案。 */
    private fun weekIndexInMonth(date: LocalDate): Int {
        val first = weekStart(date.withDayOfMonth(1))
        return ((weekStart(date).toEpochDay() - first.toEpochDay()) / 7).toInt() + 1
    }

    private fun DateRange.shift(days: Long): DateRange = DateRange(start.plusDays(days), endInclusive.plusDays(days))
    private fun DateRange.shiftMonths(months: Long): DateRange {
        val start = start.plusMonths(months)
        val end = YearMonth.from(start).atEndOfMonth()
        return DateRange(start, end)
    }
}
