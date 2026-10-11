package com.beyondguo.imprints.data

/**
 * 预置标签目录。用户可以在这些标签之外自由新建标签（见 [TagRules]），
 * 预置标签本身**不可删除**（因为无法在库层面保护它们，只能靠这个 id 列表识别）。
 *
 * [TagPreset.id] 一经发布就不能改动：Room 迁移按这些 id 种初值，备份导入用它判断预置身份。
 * 未来若要「停用」某个预置标签，只把 [TagPreset.archived] 置 true，绝不复用或删除 id。
 */
object TagCatalog {
    data class TagPreset(val id: String, val name: String, val ordinal: Int, val archived: Boolean = false)

    val PRESETS: List<TagPreset> = listOf(
        TagPreset("personal", "个人经历", 0),
        TagPreset("national", "国家大事", 1),
        TagPreset("internet", "互联网热点", 2),
        TagPreset("work", "工作", 3),
        TagPreset("travel", "旅行", 4),
    )

    /** 建库/迁移时写入的预置标签行。createdAtMs 用 0，靠 ordinal 排序。 */
    val seed: List<TagEntity> = PRESETS.map {
        TagEntity(it.id, it.name, it.ordinal, it.archived, preset = true, createdAtMs = 0)
    }

    /** 预置标签的 id 集合：用于禁止删除预置标签。 */
    val presetIds: Set<String> = PRESETS.map { it.id }.toSet()

    fun isPreset(id: String): Boolean = id in presetIds

    fun nameOf(id: String): String? = PRESETS.firstOrNull { it.id == id }?.name

    /**
     * 时间线筛选：选中集合为空表示「全部」。
     * 自定义标签只存在于数据库里，所以这里只比较 id，不校验存在性（调用方传库内有效 id）。
     */
    fun matchesFilter(selected: Set<String>, entryTagIds: Collection<String>): Boolean =
        selected.isEmpty() || entryTagIds.any { it in selected }
}
