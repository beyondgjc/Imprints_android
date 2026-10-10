package com.beyondguo.imprints.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标签目录与筛选纯逻辑；不依赖 Room / Android 框架，跑在 JVM 上。 */
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
    fun `可选标签按序号排列且与预置一致`() {
        assertEquals(TagCatalog.PRESETS.sortedBy { it.ordinal }.map { it.id }, TagCatalog.selectableIds)
    }

    @Test
    fun `seed 与预置一一对应`() {
        assertEquals(TagCatalog.PRESETS.size, TagCatalog.seed.size)
        TagCatalog.seed.forEach { tag ->
            val preset = TagCatalog.PRESETS.first { it.id == tag.id }
            assertEquals(preset.name, tag.name)
            assertEquals(preset.ordinal, tag.ordinal)
            assertEquals(preset.archived, tag.archived)
        }
    }

    @Test
    fun `名称查询命中已知标签且未知返回 null`() {
        assertEquals(TagCatalog.PRESETS.first().name, TagCatalog.nameOf(TagCatalog.PRESETS.first().id))
        assertNull(TagCatalog.nameOf("no-such-tag"))
    }

    @Test
    fun `归一化丢弃未知 id 并去重`() {
        val known = TagCatalog.selectableIds[0]
        val second = TagCatalog.selectableIds[1]
        val result = TagCatalog.normalizeSelection(listOf(known, "unknown", known, second))
        assertEquals(listOf(known, second), result)
    }

    @Test
    fun `归一化按目录顺序排序而不是输入顺序`() {
        val reversed = TagCatalog.selectableIds.reversed()
        assertEquals(TagCatalog.selectableIds, TagCatalog.normalizeSelection(reversed))
    }

    @Test
    fun `归一化空输入得到空集合`() {
        assertTrue(TagCatalog.normalizeSelection(emptyList()).isEmpty())
    }

    @Test
    fun `未选中任何标签时全部可见`() {
        assertTrue(TagCatalog.matchesFilter(emptySet(), emptyList()))
        assertTrue(TagCatalog.matchesFilter(emptySet(), listOf(TagCatalog.selectableIds[0])))
    }

    @Test
    fun `多选筛选为或语义`() {
        val a = TagCatalog.selectableIds[0]
        val b = TagCatalog.selectableIds[1]
        val c = TagCatalog.selectableIds[2]
        assertTrue(TagCatalog.matchesFilter(setOf(a, b), listOf(b)))
        assertTrue(TagCatalog.matchesFilter(setOf(a, b), listOf(c, a)))
        assertFalse(TagCatalog.matchesFilter(setOf(a, b), listOf(c)))
        // 无标签的印迹只在“全部”下显示
        assertFalse(TagCatalog.matchesFilter(setOf(a), emptyList()))
    }

    @Test
    fun `已知标签判定`() {
        assertTrue(TagCatalog.isKnown(TagCatalog.PRESETS.first().id))
        assertFalse(TagCatalog.isKnown("!!!"))
        assertFalse(TagCatalog.isKnown(""))
    }
}
