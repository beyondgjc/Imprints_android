package com.beyondguo.imprints.mail

import com.beyondguo.imprints.data.FutureLetterEntity
import com.beyondguo.imprints.data.LetterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** ML-01/ML-03/ML-07 的纯规则（JVM）：校验、快捷时间、DST 解析、状态判定。 */
class LetterRulesTest {
    private fun letter(
        deliverAtMs: Long,
        deliveredAtMs: Long? = null,
        firstOpenedAtMs: Long? = null,
        deliveryReadAtMs: Long? = null,
    ) = FutureLetterEntity(
        entryId = "e1", title = "", deliverAtMs = deliverAtMs,
        deliverLocalMinute = "2027-01-01T09:00", deliverZoneId = "UTC",
        deliveredAtMs = deliveredAtMs, firstOpenedAtMs = firstOpenedAtMs, deliveryReadAtMs = deliveryReadAtMs,
    )

    @Test
    fun bodyIsRequired() {
        assertFalse(LetterRules.canSeal("   ", 0))
        assertTrue(LetterRules.canSeal("写给未来", 0))
        assertFalse("有未处理完的附件时不能封存", LetterRules.canSeal("写给未来", 1))
    }

    @Test
    fun titleTrimsAndDefaults() {
        assertEquals("  你好  ".trim(), LetterRules.normalizeTitle("  你好  "))
        assertEquals(LetterRules.DEFAULT_TITLE, LetterRules.displayTitle("   "))
        assertEquals("给一年后的我", LetterRules.displayTitle("给一年后的我"))
    }

    @Test
    fun quickDatesClampToLastDayOfMonth() {
        // 1/31 的下月没有 31 日 → 取 2 月最后一天
        assertEquals(LocalDate.of(2026, 2, 28), LetterRules.quickDeliverDate(LocalDate.of(2026, 1, 31), 1))
        // 闰年 1/31 → 2/29
        assertEquals(LocalDate.of(2028, 2, 29), LetterRules.quickDeliverDate(LocalDate.of(2028, 1, 31), 1))
        // 一年后跨闰日：2027-02-29 不存在 → 2028-02-29 存在；2028-02-29 → 2029-02-28
        assertEquals(LocalDate.of(2029, 2, 28), LetterRules.quickDeliverDate(LocalDate.of(2028, 2, 29), 12))
        assertEquals(LocalDate.of(2027, 3, 31), LetterRules.quickDeliverDate(LocalDate.of(2026, 3, 31), 12))
    }

    @Test
    fun quickDeliverMinuteIsNineAm() {
        assertEquals("2027-03-01T09:00", LetterRules.quickDeliverMinute(LocalDate.of(2027, 2, 1), 1))
    }

    @Test
    fun resolveDeliverIsAbsoluteAndZoneAware() {
        val tokyo = LetterRules.resolveDeliver("2027-01-01T09:00", "Asia/Tokyo")
        val utc = LetterRules.resolveDeliver("2027-01-01T09:00", "UTC")
        val expectedUtc = LocalDateTime.of(2027, 1, 1, 9, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedUtc, utc.instantMs)
        // 同一本地时分，东京（+9）对应的绝对瞬间更早 9 小时；封存后不再随当前时区漂移
        assertEquals(expectedUtc - 9 * 3600 * 1000L, tokyo.instantMs)
        assertEquals("Asia/Tokyo", tokyo.zoneId)
        assertFalse(tokyo.ambiguousResolved)
    }

    @Test
    fun nonexistentLocalTimeIsRejected() {
        // 美国东部 2027-03-14 02:30 在春令时跳变中不存在
        val error = runCatching { LetterRules.resolveDeliver("2027-03-14T02:30", "America/New_York") }
            .exceptionOrNull()
        assertTrue("不存在的时分必须拒绝", error is IllegalArgumentException)
    }

    @Test
    fun ambiguousLocalTimeUsesEarlierOffset() {
        // 美国东部 2027-11-07 01:30 出现两次（EDT/EST）→ 取较早瞬间（较大偏移）
        val resolution = LetterRules.resolveDeliver("2027-11-07T01:30", "America/New_York")
        assertTrue(resolution.ambiguousResolved)
        val earlier = LocalDateTime.of(2027, 11, 7, 1, 30).toInstant(java.time.ZoneOffset.ofHours(-4)).toEpochMilli()
        val later = LocalDateTime.of(2027, 11, 7, 1, 30).toInstant(java.time.ZoneOffset.ofHours(-5)).toEpochMilli()
        assertEquals(earlier, resolution.instantMs)
        assertTrue(resolution.instantMs < later)
    }

    @Test
    fun dueIsStickyAgainstClockRollback() {
        val now = 1_000_000L
        val waiting = letter(deliverAtMs = now + 1)
        assertFalse(LetterRules.isDue(waiting, now))
        assertEquals(LetterState.WAITING, LetterRules.state(waiting, now))

        // 已确认到期（deliveredAtMs 非空）→ 即使时间回拨也不再回到未到期
        val confirmed = letter(deliverAtMs = now + 1, deliveredAtMs = now - 5)
        assertTrue(LetterRules.isDue(confirmed, 0))
        assertEquals(LetterState.DUE_UNREAD, LetterRules.state(confirmed, 0))
    }

    @Test
    fun readRequiresDueAndExplicitConfirmation() {
        val read = letter(deliverAtMs = 10, deliveredAtMs = 10, deliveryReadAtMs = 20)
        assertEquals(LetterState.DUE_READ, LetterRules.state(read, 30))
        // 提前看过（firstOpenedAtMs）不算已读
        val peeked = letter(deliverAtMs = 10_000, firstOpenedAtMs = 5)
        assertEquals(LetterState.WAITING, LetterRules.state(peeked, 6))
        assertTrue(LetterRules.isDue(peeked.copy(deliveredAtMs = 10_000), 10_001))
        assertFalse(LetterRules.isDeliveryRead(peeked))
    }

    @Test
    fun coverLabelKinds() {
        val now = System.currentTimeMillis()
        assertEquals(LetterRules.CoverLabel.TOMORROW, LetterRules.coverLabelKind(letter(now + 24 * 3600 * 1000L), now))
        assertEquals(LetterRules.CoverLabel.TODAY, LetterRules.coverLabelKind(letter(now + 60_000L), now))
        assertEquals(LetterRules.CoverLabel.DATE, LetterRules.coverLabelKind(letter(now + 5 * 24 * 3600 * 1000L), now))
        assertEquals(LetterRules.CoverLabel.DUE, LetterRules.coverLabelKind(letter(now - 1), now))
    }

    @Test
    fun remainingDaysRoundsUp() {
        val now = 0L
        assertEquals(1, LetterRules.remainingDays(60 * 60 * 1000L, now))
        assertEquals(2, LetterRules.remainingDays(25 * 60 * 60 * 1000L, now))
        assertEquals(0, LetterRules.remainingDays(now, now))
    }
}
