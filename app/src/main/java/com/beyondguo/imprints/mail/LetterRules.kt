package com.beyondguo.imprints.mail

import com.beyondguo.imprints.data.FutureLetterEntity
import com.beyondguo.imprints.data.LetterState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 信件的纯规则（ML-01 ~ ML-03、ML-07）：校验、送达时间解析、状态判定、封面文案。
 * 不碰 Android API，全部可在 JVM 上单测。
 */
object LetterRules {
    const val MAX_VISUALS = 9
    const val MAX_AUDIO = 1

    /** 默认标题：未填时展示「给未来的自己」。 */
    const val DEFAULT_TITLE = "给未来的自己"

    private val MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val MINUTE_REGEX = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

    fun isDeliverMinute(value: String): Boolean = MINUTE_REGEX.matches(value)

    /** 标题：去除首尾空白；空则用默认展示（存库保留空串即可）。 */
    fun normalizeTitle(raw: String): String = raw.trim()

    fun displayTitle(title: String): String = title.trim().ifBlank { DEFAULT_TITLE }

    /** 信件正文必填；这是信件规则，普通印迹仍可仅视觉媒体保存。 */
    fun canSeal(body: String, pendingAttachments: Int = 0): Boolean =
        body.isNotBlank() && pendingAttachments == 0

    fun validateForSeal(body: String, visualCount: Int) {
        require(body.isNotBlank()) { "信件正文不能为空" }
        require(visualCount in 0..MAX_VISUALS) { "媒体最多 $MAX_VISUALS 个" }
    }

    /** 快捷送达日：一个月后 / 一年后；目标日不存在（如 1/31 的下月）取目标月最后一天。 */
    fun quickDeliverDate(base: LocalDate, months: Long): LocalDate {
        val target = base.plusMonths(months)
        val lastDay = target.lengthOfMonth()
        return if (base.dayOfMonth > lastDay) target.withDayOfMonth(lastDay) else target
    }

    /** 快捷项固定上午 9:00，生成后可修改。 */
    fun quickDeliverMinute(base: LocalDate, months: Long, hour: Int = 9): String =
        LocalDateTime.of(quickDeliverDate(base, months), java.time.LocalTime.of(hour, 0)).format(MINUTE_FORMAT)

    /** 送达时间解析结果。[instantMs] 为封存后固定的送达瞬间。 */
    data class DeliverResolution(val instantMs: Long, val zoneId: String, val ambiguousResolved: Boolean)

    /**
     * 解析「本地时分 + 时区」到绝对瞬间（ML-07）：
     * - 时区里**不存在**的本地时分（夏令时跳变）→ 抛错，要求重新选择；
     * - **重复**的本地时分（夏令时回拨）→ 明确采用**较早**偏移，返回 ambiguousResolved = true 供 UI 说明。
     */
    fun resolveDeliver(localMinute: String, zoneId: String): DeliverResolution {
        require(isDeliverMinute(localMinute)) { "送达时间格式错误" }
        val local = LocalDateTime.parse(localMinute, MINUTE_FORMAT)
        val zone = ZoneId.of(zoneId)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该时间在所选时区不存在，请重新选择" }
        val offset = offsets.maxBy { it.totalSeconds } // 较大偏移 = 较早的绝对时刻
        val instant = local.toInstant(offset)
        return DeliverResolution(instant.toEpochMilli(), zone.id, offsets.size > 1)
    }

    /** 到期：已确认到期（deliveredAtMs 非空）或当前时间已到送达瞬间；时间回拨不回退。 */
    fun isDue(letter: FutureLetterEntity, nowMs: Long): Boolean =
        letter.deliveredAtMs != null || nowMs >= letter.deliverAtMs

    /** 到期已读：到期后主动打开正文并成功显示才记录。 */
    fun isDeliveryRead(letter: FutureLetterEntity): Boolean = letter.deliveryReadAtMs != null

    fun state(letter: FutureLetterEntity, nowMs: Long): LetterState = when {
        isDeliveryRead(letter) -> LetterState.DUE_READ
        isDue(letter, nowMs) -> LetterState.DUE_UNREAD
        else -> LetterState.WAITING
    }

    /** 封面文案：临近一天显示「明天送达」，其余显示明确日期。 */
    fun coverLabelKind(letter: FutureLetterEntity, nowMs: Long): CoverLabel = when {
        isDue(letter, nowMs) -> CoverLabel.DUE
        isSameDay(letter.deliverAtMs, nowMs) -> CoverLabel.TODAY
        isNextDay(letter.deliverAtMs, nowMs) -> CoverLabel.TOMORROW
        else -> CoverLabel.DATE
    }

    enum class CoverLabel { DUE, TODAY, TOMORROW, DATE }

    private fun isSameDay(aMs: Long, bMs: Long): Boolean = dayIndex(aMs) == dayIndex(bMs)

    private fun isNextDay(aMs: Long, bMs: Long): Boolean = dayIndex(aMs) == dayIndex(bMs) + 1

    private fun dayIndex(ms: Long): Long = ms / (24L * 60 * 60 * 1000)

    /** 剩余天数（向上取整到天），用于封面次要信息；主信息仍是日期。 */
    fun remainingDays(deliverAtMs: Long, nowMs: Long): Int =
        (((deliverAtMs - nowMs).coerceAtLeast(0) + 24L * 60 * 60 * 1000 - 1) / (24L * 60 * 60 * 1000)).toInt()
}
