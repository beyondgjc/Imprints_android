package com.beyondguo.imprints.review

import com.beyondguo.imprints.data.SourceKind
import com.beyondguo.imprints.data.SourceVisibility
import org.junit.Assert.assertEquals
import org.junit.Test

/** RV-04 统计口径：纳入去重、天数去重、标签分布、信件单列、月度求和一致。 */
class ReviewStatsTest {
    private fun entry(
        id: String,
        date: String,
        tags: List<String> = emptyList(),
        visuals: Int = 0,
        included: Boolean = true,
        featured: Boolean = false,
        visibility: String = SourceVisibility.VISIBLE.name,
    ) = SourceStatInput(
        sourceKind = SourceKind.ENTRY.name,
        sourceId = id,
        eventDate = date,
        included = included,
        visualCount = visuals,
        tagIds = tags,
        visibility = visibility,
    )

    private fun letter(id: String, date: String, included: Boolean = true) = SourceStatInput(
        sourceKind = SourceKind.LETTER.name,
        sourceId = id,
        eventDate = date,
        included = included,
        visualCount = 0,
        tagIds = emptyList(),
        visibility = SourceVisibility.VISIBLE.name,
    )

    @Test
    fun countsDedupByIdAndDays() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-10-12", tags = listOf("t1", "t2"), visuals = 2),
                entry("b", "2026-10-12", tags = listOf("t1"), visuals = 1),
                entry("c", "2026-10-13", visuals = 3),
                entry("a", "2026-10-12"), // 同一 ID 重复出现只计一次
            ),
        )
        assertEquals(3, stats.entryCount)
        assertEquals(2, stats.recordDays)
        assertEquals(6, stats.visualCount)
    }

    @Test
    fun tagDistributionMayExceedTotal() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-10-12", tags = listOf("t1", "t2")),
                entry("b", "2026-10-13", tags = listOf("t1", "t2")),
            ),
        )
        assertEquals(mapOf("t1" to 2, "t2" to 2), stats.tagCounts.toMap())
        // 同条在同标签只计 1
        val once = ReviewStatsCalculator.compute(listOf(entry("a", "2026-10-12", tags = listOf("t1", "t1"))))
        assertEquals(mapOf("t1" to 1), once.tagCounts.toMap())
    }

    @Test
    fun lettersAreCountedSeparately() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-10-12", tags = listOf("t1")),
                letter("l1", "2026-10-12"),
                letter("l2", "2026-10-14"),
            ),
        )
        assertEquals(1, stats.entryCount)
        assertEquals(1, stats.recordDays) // 信件不计入记录天数
        assertEquals(2, stats.letterCount)
        // 信件不并入标签分布：只有印迹的 t1
        assertEquals(mapOf("t1" to 1), stats.tagCounts.toMap())
    }

    @Test
    fun excludedAndHiddenSourcesDropOut() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-10-12", included = true),
                entry("b", "2026-10-12", included = false), // 取消纳入
                entry("c", "2026-10-12", visibility = SourceVisibility.RECYCLED.name), // 来源进回收站：贡献立即消失
                entry("d", "2026-10-12", visibility = SourceVisibility.PURGED.name),
                letter("l1", "2026-10-12", included = false),
            ),
        )
        assertEquals(1, stats.entryCount)
        assertEquals(0, stats.letterCount)
    }

    @Test
    fun featuredDoesNotAffectStatistics() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-10-12", featured = true),
                entry("b", "2026-10-12", featured = false),
            ),
        )
        assertEquals(2, stats.entryCount)
    }

    @Test
    fun monthlyCountsSumEqualsEntryCount() {
        val stats = ReviewStatsCalculator.compute(
            listOf(
                entry("a", "2026-01-31"),
                entry("b", "2026-02-01"),
                entry("c", "2026-02-28"),
                entry("d", "2026-12-31"),
            ),
        )
        assertEquals(4, stats.monthlyCounts.sumOf { it.second })
        assertEquals(mapOf("2026-01" to 1, "2026-02" to 2, "2026-12" to 1), stats.monthlyCounts.toMap())
    }
}
