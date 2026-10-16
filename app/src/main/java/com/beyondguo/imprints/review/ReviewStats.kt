package com.beyondguo.imprints.review

import com.beyondguo.imprints.data.SourceKind
import com.beyondguo.imprints.data.SourceVisibility

/** 统计输入：一条纳入快照的来源。统计只描述**已记录并纳入的资料**，不推断心情或生活占比。 */
data class SourceStatInput(
    val sourceKind: String,
    val sourceId: String,
    /** ISO yyyy-MM-dd */
    val eventDate: String,
    val included: Boolean,
    /** 主媒体数量；动态照片主图＋附属视频合计计 1 */
    val visualCount: Int,
    /** 保存时的标签 id 快照 */
    val tagIds: List<String>,
    val visibility: String,
)

data class ReviewStats(
    /** 纳入的普通印迹数（ID 去重） */
    val entryCount: Int,
    /** 上述印迹的事件日期去重数 */
    val recordDays: Int,
    /** 上述印迹的主媒体数之和 */
    val visualCount: Int,
    /** 标签 id → 数量；同条在同标签只计 1，多标签各自计入，合计可大于印迹总数 */
    val tagCounts: List<Pair<String, Int>>,
    /** 用户主动加入且当前可用的信件片段数（单列） */
    val letterCount: Int,
    /** 年回顾：yyyy-MM → 计数；各月之和必须等于 entryCount，无记录的月份由 UI 补 0 */
    val monthlyCounts: List<Pair<String, Int>>,
)

/**
 * 回顾统计口径（RV-04）。纯函数：
 * - 只统计 `included`（纳入统计）且 `VISIBLE` 的来源——来源进回收站时其统计贡献**立即消失**；
 * - 「展示为重点片段」不参与统计口径；
 * - 信件片段单列，不并入普通印迹数/记录天数/标签分布。
 */
object ReviewStatsCalculator {
    fun compute(sources: List<SourceStatInput>, orderMonthly: Boolean = false): ReviewStats {
        val visible = sources.filter { it.visibility == SourceVisibility.VISIBLE.name }
        val entries = visible.filter { it.sourceKind == SourceKind.ENTRY.name && it.included }
            .distinctBy { it.sourceId }
        val letters = visible.filter { it.sourceKind == SourceKind.LETTER.name && it.included }
            .distinctBy { it.sourceId }

        val tagCounts = entries.flatMap { source -> source.tagIds.distinct() }
            .groupingBy { it }.eachCount()
            .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        val monthly = entries.groupingBy { it.eventDate.take(7) }.eachCount()
            .toList().sortedBy { it.first }

        return ReviewStats(
            entryCount = entries.size,
            recordDays = entries.map { it.eventDate }.toSet().size,
            visualCount = entries.sumOf { it.visualCount },
            tagCounts = tagCounts,
            letterCount = letters.size,
            monthlyCounts = if (orderMonthly) monthly.sortedBy { it.first } else monthly,
        )
    }
}
