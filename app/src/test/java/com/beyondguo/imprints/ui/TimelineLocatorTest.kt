package com.beyondguo.imprints.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLocatorTest {
    @Test fun firstMonthIncludesStickyHeaderOffset() {
        // 结构：0=页头，1=首月吸顶，2=首条
        val indexes = buildTimelineIndexes(listOf("a" to "2026-09", "b" to "2026-09"))
        assertEquals(2, indexes["a"])
        assertEquals(3, indexes["b"])
    }

    @Test fun multiMonthGroupsInsertStickyPerMonth() {
        val indexes = buildTimelineIndexes(listOf(
            "a" to "2026-09",
            "b" to "2026-09",
            "c" to "2026-08",
            "d" to "2026-08",
            "e" to "2026-07",
        ))
        // header, sticky09, a, b, sticky08, c, d, sticky07, e
        assertEquals(2, indexes["a"])
        assertEquals(3, indexes["b"])
        assertEquals(5, indexes["c"])
        assertEquals(6, indexes["d"])
        assertEquals(8, indexes["e"])
    }

    @Test fun emptyEntriesYieldNoIndexes() {
        assertEquals(emptyMap<String, Int>(), buildTimelineIndexes(emptyList()))
    }
}
