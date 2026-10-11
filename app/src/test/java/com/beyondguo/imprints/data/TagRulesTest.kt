package com.beyondguo.imprints.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标签命名与排序纯规则；不依赖 Room / Android 框架。 */
class TagRulesTest {

    private fun tag(id: String, name: String, ordinal: Int = 0, preset: Boolean = false, createdAtMs: Long = 0) =
        TagEntity(id = id, name = name, ordinal = ordinal, preset = preset, createdAtMs = createdAtMs)

    @Test
    fun `名称去掉首尾空白并压缩内部空白`() {
        assertEquals("旅行", TagRules.normalizeName("  旅行  "))
        assertEquals("国家 大事", TagRules.normalizeName("国家   大事"))
        assertEquals("旅行", TagRules.normalizeName("旅行\n"))
    }

    @Test
    fun `比较键忽略大小写与全半角`() {
        assertEquals(TagRules.compareKey("TRAVEL"), TagRules.compareKey("travel"))
        // 全角字母/数字经 NFKC 归一后与半角相同
        assertEquals(TagRules.compareKey("ABC123"), TagRules.compareKey("ＡＢＣ１２３"))
    }

    @Test
    fun `字符数按码点计算`() {
        assertEquals(20, TagRules.lengthOf("一".repeat(20)))
        // emoji 是代理对，按码点算仍是 1 个字符
        assertEquals(2, TagRules.lengthOf("😀😀"))
        // 20 个 emoji 属于合法长度；若按 UTF-16 长度算会被误判为 40
        val emojis = "😀".repeat(20)
        assertEquals(20, TagRules.lengthOf(emojis))
        assertTrue(TagRules.isValidName(emojis))
    }

    @Test
    fun `名称长度上限为 20 字`() {
        assertTrue(TagRules.isValidName("一".repeat(20)))
        assertFalse(TagRules.isValidName("一".repeat(21)))
        assertFalse(TagRules.isValidName(""))
    }

    @Test
    fun `空名称报错`() {
        assertEquals("标签名不能为空", TagRules.validationError(emptyList(), "   "))
    }

    @Test
    fun `超长名称报错`() {
        val error = TagRules.validationError(emptyList(), "一".repeat(21))
        assertNotNull(error)
        assertTrue(error!!.contains("20"))
    }

    @Test
    fun `同名标签报错并提示已有名称`() {
        val tags = listOf(tag("a", "旅行"))
        val error = TagRules.validationError(tags, "旅行")
        assertNotNull(error)
        assertTrue(error!!.contains("旅行"))
    }

    @Test
    fun `重名判断忽略大小写与全半角`() {
        val tags = listOf(tag("a", "Travel"), tag("b", "ＡＢＣ"))
        assertNotNull(TagRules.validationError(tags, "travel"))
        assertNotNull(TagRules.validationError(tags, "abc"))
    }

    @Test
    fun `改名时排除自己不算重名`() {
        val tags = listOf(tag("a", "旅行"), tag("b", "工作"))
        assertNull(TagRules.validationError(tags, "旅行", editingId = "a"))
        assertNotNull(TagRules.validationError(tags, "工作", editingId = "a"))
    }

    @Test
    fun `合法名称通过校验`() {
        assertNull(TagRules.validationError(listOf(tag("a", "旅行")), "读书"))
    }

    @Test
    fun `比较键忽略首尾空白`() {
        // 曾经的 bug：compareKey 不去空白，导致 findByName(" 工作 ") 匹配不到「工作」
        assertEquals(TagRules.compareKey("工作"), TagRules.compareKey("  工作  "))
        assertEquals(TagRules.compareKey("国家 大事"), TagRules.compareKey("国家   大事"))
    }

    @Test
    fun `按名称查找已有标签`() {
        // ordinal 不同，保证查找结果与展示顺序一致
        val travel = tag("a", "旅行", ordinal = 0)
        val work = tag("b", "工作", ordinal = 1)
        val tags = listOf(work, travel)
        assertEquals(work.id, TagRules.findByName(tags, " 工作 ")?.id)
        assertEquals(travel.id, TagRules.findByName(tags, "旅行")?.id)
        assertNull(TagRules.findByName(tags, "读书"))
    }

    @Test
    fun `数量上限判定`() {
        assertFalse(TagRules.hasReachedLimit(0))
        assertFalse(TagRules.hasReachedLimit(TagRules.MAX_TAG_COUNT - 1))
        assertTrue(TagRules.hasReachedLimit(TagRules.MAX_TAG_COUNT))
        assertTrue(TagRules.hasReachedLimit(TagRules.MAX_TAG_COUNT + 5))
    }

    @Test
    fun `排序为预置在前、自定义按创建时间`() {
        val tags = listOf(
            tag("custom-late", "读书", preset = false, createdAtMs = 200),
            tag("preset-b", "工作", ordinal = 3, preset = true),
            tag("custom-early", "运动", preset = false, createdAtMs = 100),
            tag("preset-a", "个人经历", ordinal = 0, preset = true),
        )
        assertEquals(
            listOf("preset-a", "preset-b", "custom-early", "custom-late"),
            tags.sortedWith(TagRules.TAG_ORDER).map { it.id },
        )
    }

    @Test
    fun `归一化按目录顺序输出并丢弃库内不存在的 id`() {
        val tags = listOf(
            tag("preset-a", "个人经历", ordinal = 0, preset = true),
            tag("custom", "读书", preset = false, createdAtMs = 100),
        )
        // 输入顺序打乱、含未知 id，输出应是目录顺序且只保留存在的
        assertEquals(listOf("preset-a", "custom"), TagRules.normalizeSelection(tags, listOf("custom", "ghost", "preset-a")))
        assertEquals(listOf("custom"), TagRules.normalizeSelection(tags, listOf("custom")))
        assertTrue(TagRules.normalizeSelection(tags, emptyList()).isEmpty())
    }
}
