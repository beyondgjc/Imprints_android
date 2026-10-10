package com.beyondguo.imprints.data

/**
 * 预置标签目录（当前版本不支持用户自建标签）。
 *
 * 每个预置标签的 [TagPreset.id] 一经发布就不能改动：
 * - Room 迁移按这些 id 写入初值；
 * - 备份导出/导入用它做白名单校验；
 * - 未来若要「停用」某个标签，只把 [TagPreset.archived] 置 true，绝不复用或删除 id。
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

    /** 时间线筛选栏与编辑页可选的标签，按 [TagPreset.ordinal] 排序；不含已停用标签。 */
    val selectableIds: List<String> = PRESETS.filterNot { it.archived }.sortedBy { it.ordinal }.map { it.id }

    private val byId: Map<String, TagPreset> = PRESETS.associateBy { it.id }

    val seed: List<TagEntity> = PRESETS.map { TagEntity(it.id, it.name, it.ordinal, it.archived) }

    fun nameOf(id: String): String? = byId[id]?.name

    /** 是否是可挂到印迹上的预置标签（未知 id 一律视为脏数据丢弃）。 */
    fun isKnown(id: String): Boolean = byId[id]?.archived == false

    /**
     * 归一化一条印迹的标签集合：丢弃未知/已停用标签、去重，并按目录顺序排序。
     * 时间线分组与编辑页回填都依赖这里的稳定顺序。
     */
    fun normalizeSelection(ids: Collection<String>): List<String> =
        ids.filter { isKnown(it) }.distinct().sortedBy { byId.getValue(it).ordinal }

    /**
     * 时间线筛选：选中集合为空表示「全部」。[selected] 与 [entryTagIds] 都用 id 比较。
     * 采用 OR 语义：只要命中任一选中标签就显示。
     */
    fun matchesFilter(selected: Set<String>, entryTagIds: Collection<String>): Boolean =
        selected.isEmpty() || entryTagIds.any { it in selected }
}
