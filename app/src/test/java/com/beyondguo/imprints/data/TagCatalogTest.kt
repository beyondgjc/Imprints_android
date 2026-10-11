package com.beyondguo.imprints.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预置标签目录：id 稳定性、seed 内容与筛选语义。 */
class TagCatalogTest {

    @Test
    fun `预置标签有唯一 id、唯一名称与连续序号`() {
        val presets = TagCatalog.PRESETS
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        assertEquals(presets.size, presets.map { it.name }.distinct().size)
        assertEquals(presets.indices.toList(), presets.map { it.ordinal }.sorted())
        assertTrue(presets.none { it.archived })
    }

    @Test
    fun `seed 全部标记为预置且序号与目录一致`() {
        assertEquals(TagCatalog.PRESETS.size, TagCatalog.seed.size)
        assertTrue(TagCatalog.seed.all { it.preset })
        assertTrue(TagCatalog.seed.all { it.createdAtMs == 0L })
        TagCatalog.seed.forEach { tag ->
            val preset = TagCatalog.PRESETS.first { it.id == tag.id }
            assertEquals(preset.name, tag.name)
            assertEquals(preset.ordinal, tag.ordinal)
            assertEquals(preset.archived, tag.archived)
        }
    }

    @Test
    fun `预置 id 判定`() {
        TagCatalog.PRESETS.forEach { assertTrue(TagCatalog.isPreset(it.id)) }
        assertFalse(TagCatalog.isPreset("no-such-tag"))
        assertFalse(TagCatalog.isPreset(""))
    }

    @Test
    fun `名称查询命中已知标签且未知返回 null`() {
        assertEquals(TagCatalog.PRESETS.first().name, TagCatalog.nameOf(TagCatalog.PRESETS.first().id))
        assertNull(TagCatalog.nameOf("no-such-tag"))
    }

    @Test
    fun `未选中任何标签时全部可见`() {
        assertTrue(TagCatalog.matchesFilter(emptySet(), emptyList()))
        assertTrue(TagCatalog.matchesFilter(emptySet(), listOf("anything")))
    }

    @Test
    fun `多选筛选为或语义`() {
        assertTrue(TagCatalog.matchesFilter(setOf("a", "b"), listOf("b")))
        assertTrue(TagCatalog.matchesFilter(setOf("a", "b"), listOf("c", "a")))
        assertFalse(TagCatalog.matchesFilter(setOf("a", "b"), listOf("c")))
        // 无标签的印迹只在「全部」下显示
        assertFalse(TagCatalog.matchesFilter(setOf("a"), emptyList()))
    }
}
