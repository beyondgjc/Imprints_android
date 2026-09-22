package com.beyondguo.imprints.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRulesTest {
    private val minute = "2026-09-21T15:42"

    @Test fun saveAllowsBodyOnly() {
        EntryRules.validateForSave("正文", 0, minute)
        assertTrue(EntryRules.canSave("正文", 0))
    }

    @Test fun saveAllowsVisualOnly() {
        EntryRules.validateForSave("", 1, minute)
        assertTrue(EntryRules.canSave("", 1))
    }

    @Test fun saveRejectsAudioPlaceFeelingOnly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EntryRules.validateForSave("   ", 0, minute)
        }
        assertEquals("请填写正文或添加图片、动图、视频", error.message)
        assertFalse(EntryRules.canSave("   ", 0))
    }

    @Test fun saveRejectsTenthVisual() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EntryRules.validateForSave("x", 10, minute)
        }
        assertEquals("媒体最多 9 个", error.message)
        EntryRules.validateForSave("x", 9, minute)
    }

    @Test fun saveRejectsInvalidEventMinute() {
        assertThrows(IllegalArgumentException::class.java) { EntryRules.validateForSave("x", 0, "2026-09-21") }
        assertThrows(IllegalArgumentException::class.java) { EntryRules.validateForSave("x", 0, "2026-09-21T15:42:00") }
        assertTrue(EntryRules.isEventMinute(minute))
        assertFalse(EntryRules.isEventMinute("2026-9-2T1:2"))
    }

    @Test fun saveBlockedWhenFeelingLockedWithText() {
        assertTrue(EntryRules.canSave("正文", 0, feelingLockedWithText = false))
        assertFalse(EntryRules.canSave("正文", 0, feelingLockedWithText = true))
        assertFalse(EntryRules.canSave("", 1, feelingLockedWithText = true))
    }

    @Test fun searchQueryBuildsAndEscapes() {
        assertNull(EntryRules.buildBodySearchQuery("   "))
        assertEquals("\"咖啡\"", EntryRules.buildBodySearchQuery("咖啡"))
        assertEquals("\"海边\" AND \"日落\"", EntryRules.buildBodySearchQuery("海边 日落"))
        assertEquals("\"a\"\"b\"", EntryRules.buildBodySearchQuery("a\"b"))
        // 词内每个 " 扩成 ""，再整体包一对引号
        assertEquals("\"\"\"\"\"\"", EntryRules.buildBodySearchQuery("\"\""))
    }

    @Test fun recycleWindowBoundaries() {
        val now = 1_700_000_000_000L
        val cutoff = now - EntryRules.RETENTION_MS
        assertEquals(cutoff, EntryRules.recycleCutoff(now))
        assertTrue(EntryRules.isRestorable(cutoff + 1, now))
        assertFalse(EntryRules.isRestorable(cutoff, now))
        assertFalse(EntryRules.isRestorable(cutoff - 1, now))
        assertTrue(EntryRules.isExpired(cutoff, now))
        assertTrue(EntryRules.isExpired(cutoff - 1, now))
        assertFalse(EntryRules.isExpired(cutoff + 1, now))
        assertFalse(EntryRules.isExpired(null, now))
    }

    @Test fun recycleRemainingLabelGranularity() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals("剩余 7 天", EntryRules.recycleRemainingLabel(now, now))
        assertEquals("剩余 1 天", EntryRules.recycleRemainingLabel(now - EntryRules.RETENTION_MS + day, now))
        assertEquals("不足 1 天", EntryRules.recycleRemainingLabel(now - EntryRules.RETENTION_MS + day - 1, now))
        assertEquals("不足 1 天", EntryRules.recycleRemainingLabel(now - EntryRules.RETENTION_MS, now - 1_000))
    }
}
