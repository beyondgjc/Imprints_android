package com.beyondguo.imprints.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** RV-02 的日期范围纯规则：周起点固定周一、已结束周期、边界与闰年。 */
class ReviewPeriodsTest {
    private val monday = LocalDate.of(2026, 10, 12) // 周一
    private val sunday = LocalDate.of(2026, 10, 18) // 周日

    @Test
    fun weekAlwaysStartsOnMonday() {
        assertEquals(monday, ReviewPeriods.weekStart(monday))
        assertEquals(monday, ReviewPeriods.weekStart(sunday))
        assertEquals(monday, ReviewPeriods.weekStart(LocalDate.of(2026, 10, 15)))
        val week = ReviewPeriods.weekOf(sunday)
        assertEquals(monday, week.start)
        assertEquals(sunday, week.endInclusive)
        assertEquals(7, week.days)
    }

    @Test
    fun lastCompletedRanges() {
        // 今天 = 周日：本周尚未结束 → 最近已结束的是上一周
        val week = ReviewPeriods.lastCompletedWeek(sunday)
        assertEquals(LocalDate.of(2026, 10, 5), week.start)
        assertEquals(LocalDate.of(2026, 10, 11), week.endInclusive)
        // 周一当天：本周刚开始，最近已结束的仍是上一周
        assertEquals(LocalDate.of(2026, 10, 5), ReviewPeriods.lastCompletedWeek(monday).start)

        val month = ReviewPeriods.lastCompletedMonth(LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 9, 1), month.start)
        assertEquals(LocalDate.of(2026, 9, 30), month.endInclusive)

        val year = ReviewPeriods.lastCompletedYear(LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2025, 1, 1), year.start)
        assertEquals(LocalDate.of(2025, 12, 31), year.endInclusive)
    }

    @Test
    fun crossYearWeekIsWhole() {
        // 2026-01-01 是周四：所在周横跨 2025-12-29 ~ 2026-01-04
        val week = ReviewPeriods.weekOf(LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2025, 12, 29), week.start)
        assertEquals(LocalDate.of(2026, 1, 4), week.endInclusive)
    }

    @Test
    fun leapYearMonthAndYear() {
        val feb = ReviewPeriods.monthOf(LocalDate.of(2028, 2, 10))
        assertEquals(LocalDate.of(2028, 2, 29), feb.endInclusive)
        val year = ReviewPeriods.yearOf(LocalDate.of(2028, 2, 10))
        assertEquals(366, year.days)
    }

    @Test
    fun boundariesIncludeLastDayAndExcludeNextDay() {
        val range = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertTrue(range.contains(LocalDate.of(2026, 10, 12)))
        assertTrue("末日 23:59 归属当日", range.contains(LocalDate.of(2026, 10, 18)))
        assertTrue(!range.contains(LocalDate.of(2026, 10, 19)))
        assertTrue(!range.contains(LocalDate.of(2026, 10, 11)))
    }

    @Test
    fun completedDetection() {
        val today = LocalDate.of(2026, 10, 15)
        assertTrue(ReviewPeriods.isCompleted(ReviewPeriods.lastCompletedWeek(today), today))
        assertTrue(!ReviewPeriods.isCompleted(ReviewPeriods.weekOf(today), today))
    }

    @Test
    fun customRangeValidation() {
        val today = LocalDate.of(2026, 10, 15)
        ReviewPeriods.validateCustom(LocalDate.of(2026, 1, 1), today, today) // 通过
        val error = runCatching {
            ReviewPeriods.validateCustom(LocalDate.of(2026, 10, 16), today, today)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val error2 = runCatching {
            ReviewPeriods.validateCustom(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9), today)
        }.exceptionOrNull()
        assertTrue(error2 is IllegalArgumentException)
    }

    @Test
    fun groupingByWeekAndMonth() {
        val month = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        val weeks = ReviewPeriods.weeksOf(month)
        // 9/1（周二）所在周截到 9/1 起，9/30 收尾
        assertEquals(LocalDate.of(2026, 9, 1), weeks.first().start)
        assertEquals(LocalDate.of(2026, 9, 6), weeks.first().endInclusive)
        assertEquals(LocalDate.of(2026, 9, 30), weeks.last().endInclusive)
        assertEquals(weeks.size, weeks.map { it.start }.toSet().size)

        val months = ReviewPeriods.monthsOf(ReviewPeriods.yearOf(LocalDate.of(2026, 6, 1)))
        assertEquals(12, months.size)
    }

    @Test
    fun invitationPrefersYearThenMonthThenWeek() {
        val today = LocalDate.of(2026, 10, 15)
        val lastYear = LocalDate.of(2025, 6, 1).toString()
        val lastMonth = LocalDate.of(2026, 9, 10).toString()
        val lastWeek = LocalDate.of(2026, 10, 6).toString()

        val all = ReviewPeriods.invitationRange(today, listOf(lastYear, lastMonth, lastWeek)) { _, _ -> false }
        assertEquals(ReviewPeriods.YEAR, all!!.first)

        val noYear = ReviewPeriods.invitationRange(today, listOf(lastMonth, lastWeek)) { _, _ -> false }
        assertEquals(ReviewPeriods.MONTH, noYear!!.first)

        val onlyWeek = ReviewPeriods.invitationRange(today, listOf(lastWeek)) { _, _ -> false }
        assertEquals(ReviewPeriods.WEEK, onlyWeek!!.first)

        val none = ReviewPeriods.invitationRange(today, listOf(today.toString())) { _, _ -> false }
        assertNull(none)

        // 已关闭的周期跳过
        val closed = ReviewPeriods.invitationRange(today, listOf(lastYear, lastMonth)) { kind, _ -> kind == ReviewPeriods.YEAR }
        assertEquals(ReviewPeriods.MONTH, closed!!.first)
    }

    @Test
    fun dateKeyUsesEventDate() {
        assertEquals("2026-10-12", ReviewPeriods.dateKey("2026-10-12T23:59"))
    }
}
