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

    /**
     * 当前启用的预置标签：家事 / 国事 / 天下事。
     *
     * 命名沿用最初发布时的 id（personal / national / internet），只改展示名：
     * 这三条在旧库里可能已经被印迹引用，换 id 会丢掉挂载关系。
     * 「工作 / 旅行」两个旧预置改为停用（见 [RETIRED_IDS]），不再出现在选择与筛选里。
     */
    val PRESETS: List<TagPreset> = listOf(
        TagPreset("personal", "家事", 0),
        TagPreset("national", "国事", 1),
        TagPreset("internet", "天下事", 2),
    )

    /**
     * 曾经发布过、现已停用的预置标签 id。
     *
     * 仍然算「预置」，因而依旧不可删除；旧备份导入时也仍按预置身份处理。
     * 已在库里的行由 [ImprintDatabase.Companion.MIGRATION_4_5] 置 archived = 1 停用。
     */
    private val RETIRED_IDS: Set<String> = setOf("work", "travel")

    /**
     * 停用的预置标签在库中的排序位。用 90+ 是为了与启用项（0..n）拉开距离：
     * 全部标签页（`allTags()`）会把它们排在列表末尾，而不是插在启用项中间。
     */
    internal const val RETIRED_ORDINAL_BASE = 90

    /** 建库/迁移时写入的预置标签行。createdAtMs 用 0，靠 ordinal 排序。 */
    val seed: List<TagEntity> = PRESETS.map {
        TagEntity(it.id, it.name, it.ordinal, it.archived, preset = true, createdAtMs = 0)
    }

    /** 预置标签的 id 集合（含已停用项）：用于禁止删除预置标签。 */
    val presetIds: Set<String> = PRESETS.map { it.id }.toSet() + RETIRED_IDS

    fun isPreset(id: String): Boolean = id in presetIds

    fun nameOf(id: String): String? = PRESETS.firstOrNull { it.id == id }?.name

    /**
     * 时间线筛选：选中集合为空表示「全部」。
     * 自定义标签只存在于数据库里，所以这里只比较 id，不校验存在性（调用方传库内有效 id）。
     */
    fun matchesFilter(selected: Set<String>, entryTagIds: Collection<String>): Boolean =
        selected.isEmpty() || entryTagIds.any { it in selected }
}
