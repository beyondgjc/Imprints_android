package com.beyondguo.imprints.data

/**
 * 来源删除向总结的传播（RV-06）。
 *
 * 快照是「当时整理的作品」，来源变更不静默改写；但**删除例外**必须严格跟随来源可见性：
 * - 来源进回收站 → 立即隐藏原文/媒体/封面/统计贡献（RECYCLED，行保留）；
 * - 7 天内恢复 → 恢复原快照（回到 VISIBLE，**不**覆盖为最新原文）；
 * - 彻底删除 → 清空快照正文与媒体引用，保留无内容占位（PURGED tombstone）。
 *
 * 这些函数挂在 [MailReviewDao] 上，供 ImprintRepository / LetterRepository / ReviewRepository 共用，
 * 保证三处删除路径行为一致。
 */

internal suspend fun MailReviewDao.onSourceRecycled(sourceKind: String, sourceId: String) {
    sourcesOf(sourceKind, sourceId).forEach { row ->
        if (row.visibility != SourceVisibility.PURGED.name) {
            upsertSource(row.copy(visibility = SourceVisibility.RECYCLED.name))
        }
    }
}

internal suspend fun MailReviewDao.onSourceRestored(sourceKind: String, sourceId: String) {
    sourcesOf(sourceKind, sourceId).forEach { row ->
        if (row.visibility == SourceVisibility.RECYCLED.name) {
            upsertSource(row.copy(visibility = SourceVisibility.VISIBLE.name))
        }
    }
}

/**
 * 来源彻底删除：清空所有总结里的对应快照原文、媒体引用，保留无内容占位；
 * 返回因此不再被引用的媒体路径，由调用方在确认无其他引用后物理删除。
 */
internal suspend fun MailReviewDao.onSourcePurged(sourceKind: String, sourceId: String): List<String> {
    val released = sourceMediaOf(sourceKind, sourceId).map { it.relativePath }
    sourcesOf(sourceKind, sourceId).forEach { row ->
        upsertSource(
            row.copy(
                visibility = SourceVisibility.PURGED.name,
                titleSnapshot = "",
                bodySnapshot = "",
                tagSnapshot = "",
                contentHash = "",
            ),
        )
        released.forEach { path -> deleteMediaRef(OWNER_REVIEW, row.reviewId, path) }
    }
    deleteSourceMediaOf(sourceKind, sourceId)
    return released
}

/** 来源内容变化（不改可见性）：只更新版本指纹，供「来源记录有变化」提示。 */
internal suspend fun MailReviewDao.touchSourceVersion(sourceKind: String, sourceId: String, updatedAtMs: Long, contentHash: String) {
    sourcesOf(sourceKind, sourceId).forEach { row ->
        upsertSource(row.copy(sourceUpdatedAtMs = updatedAtMs, contentHash = contentHash))
    }
}

internal const val OWNER_ENTRY = "ENTRY"
internal const val OWNER_LETTER = "LETTER"
internal const val OWNER_REVIEW = "REVIEW"
internal const val OWNER_DRAFT = "DRAFT"

internal fun ownerKindOf(kind: ContentKind): String = when (kind) {
    ContentKind.ENTRY -> OWNER_ENTRY
    ContentKind.LETTER -> OWNER_LETTER
    ContentKind.REVIEW -> OWNER_REVIEW
}
