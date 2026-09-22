package com.beyondguo.imprints.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLocatorTest {
    @Test fun firstMonthIncludesStickyHeaderOffset() {
        // 结构：0=页头，1=定位条，2=首月吸顶，3=首条
        val indexes = buildTimelineIndexes(listOf("a" to "2026-09", "b" to "2026-09"))
        assertEquals(3, indexes["a"])
        assertEquals(4, indexes["b"])
    }

    @Test fun multiMonthGroupsInsertStickyPerMonth() {
        val indexes = buildTimelineIndexes(listOf(
            "a" to "2026-09",
            "b" to "2026-09",
            "c" to "2026-08",
            "d" to "2026-08",
            "e" to "2026-07",
        ))
        // header, locators, sticky09, a, b, sticky08, c, d, sticky07, e
        assertEquals(3, indexes["a"])
        assertEquals(4, indexes["b"])
        assertEquals(6, indexes["c"])
        assertEquals(7, indexes["d"])
        assertEquals(9, indexes["e"])
    }

    @Test fun emptyEntriesYieldNoIndexes() {
        assertEquals(emptyMap<String, Int>(), buildTimelineIndexes(emptyList()))
    }
}
