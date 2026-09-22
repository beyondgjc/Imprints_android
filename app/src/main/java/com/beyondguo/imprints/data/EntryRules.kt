package com.beyondguo.imprints.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 保存校验、回收站与正文搜索的纯规则，供 Repository / UI 共用并可在 JVM 上单测。 */
object EntryRules {
    const val MAX_VISUALS = 9
    const val MAX_AUDIO = 1
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private val EVENT_MINUTE = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
    private val EVENT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun isEventMinute(value: String): Boolean = EVENT_MINUTE.matches(value)

    fun validateEventMinute(value: String) {
        require(isEventMinute(value)) { "事件时间格式错误" }
        LocalDateTime.parse(value, EVENT_FORMAT)
    }

    fun validateForSave(body: String, visualCount: Int, eventLocalMinute: String) {
        require(body.isNotBlank() || visualCount > 0) { "请填写正文或添加图片、动图、视频" }
        require(visualCount in 0..MAX_VISUALS) { "媒体最多 $MAX_VISUALS 个" }
        validateEventMinute(eventLocalMinute)
    }

    fun canSave(body: String, visualCount: Int, feelingLockedWithText: Boolean = false): Boolean =
        (body.isNotBlank() || visualCount > 0) && !feelingLockedWithText

    fun buildBodySearchQuery(input: String): String? {
        val terms = input.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }
    }

    fun recycleCutoff(nowMs: Long): Long = nowMs - RETENTION_MS

    fun isExpired(deletedAtMs: Long?, nowMs: Long): Boolean =
        deletedAtMs != null && deletedAtMs <= recycleCutoff(nowMs)

    fun isRestorable(deletedAtMs: Long?, nowMs: Long): Boolean =
        deletedAtMs != null && deletedAtMs > recycleCutoff(nowMs)

    fun recycleRemainingLabel(deletedAtMs: Long, nowMs: Long): String {
        val remaining = (deletedAtMs + RETENTION_MS - nowMs).coerceAtLeast(0)
        val days = remaining / (24 * 60 * 60 * 1000)
        return if (days >= 1) "剩余 $days 天" else "不足 1 天"
    }
}
