package com.beyondguo.imprints.data

import java.text.Normalizer
import java.util.Locale

/**
 * 标签命名与挂载的纯规则，供 Repository / UI 共用，可在 JVM 上单测。
 * 只依赖 [TagEntity] 这个纯数据类，不碰 Room 与 Android 框架。
 */
object TagRules {
    const val MAX_NAME_LENGTH = 20
    const val MAX_TAG_COUNT = 50

    /**
     * 去掉首尾空白并把连续空白压成一个空格（用户误输的换行/多空格不进入标签名）。
     * 内部空白不被删除，因为「国家 大事」和「国家大事」是用户有意的区别。
     */
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("""\s+"""), " ")

    /**
     * 比较用键：先去首尾空白/压缩空白，再 NFKC（全角→半角）+ 小写。
     * 只用于判断重复与查找，不用于存储。
     */
    fun compareKey(name: String): String =
        Normalizer.normalize(normalizeName(name), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** 字符数按码点算，避免 emoji 被算成两个字符。 */
    fun lengthOf(name: String): Int = name.codePointCount(0, name.length)

    fun isValidName(name: String): Boolean = name.isNotEmpty() && lengthOf(name) <= MAX_NAME_LENGTH

    fun hasReachedLimit(activeTagCount: Int): Boolean = activeTagCount >= MAX_TAG_COUNT

    /** 在同一批标签里按名称找已有标签（重名不新建，直接复用）。 */
    fun findByName(tags: List<TagEntity>, name: String): TagEntity? {
        val key = compareKey(name)
        return tags.firstOrNull { compareKey(it.name) == key }
    }

    /**
     * 校验一个准备写入的标签名，返回给用户看的错误文案；通过则返回 null。
     * [tags] 传当前库内全部标签（含已停用），[editingId] 是改名时排除自己的 id。
     */
    fun validationError(tags: List<TagEntity>, name: String, editingId: String? = null): String? {
        val trimmed = normalizeName(name)
        if (trimmed.isEmpty()) return "标签名不能为空"
        if (!isValidName(trimmed)) return "标签名最多 $MAX_NAME_LENGTH 个字"
        val key = compareKey(trimmed)
        val duplicate = tags.firstOrNull { it.id != editingId && compareKey(it.name) == key }
        if (duplicate != null) return "已有同名标签「${duplicate.name}」"
        return null
    }

    /** 归一化一条印迹的标签集合：按目录顺序（预置在前、自定义按创建时间）排序。 */
    fun normalizeSelection(tags: List<TagEntity>, ids: Collection<String>): List<String> {
        val order = tags.sortedWith(TAG_ORDER).map { it.id }
        val wanted = ids.toSet()
        return order.filter { it in wanted }
    }

    /**
     * 标签展示顺序：预置按 [TagEntity.ordinal] 在前，自定义标签按创建时间在后，最后用 id 兜底保证稳定。
     */
    val TAG_ORDER: Comparator<TagEntity> = compareByDescending<TagEntity> { it.preset }
        .thenBy { it.ordinal }
        .thenBy { it.createdAtMs }
        .thenBy { it.id }
}
