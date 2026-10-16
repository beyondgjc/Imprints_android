package com.beyondguo.imprints.data

import androidx.room.withTransaction
import com.beyondguo.imprints.review.ReviewStats
import com.beyondguo.imprints.review.ReviewStatsCalculator
import com.beyondguo.imprints.review.SourceStatInput
import com.beyondguo.imprints.review.DateRange
import com.beyondguo.imprints.review.ReviewPeriods
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** 来源键：与 [ReviewEntity.coverSourceKey] 同格式 "ENTRY:<id>" / "LETTER:<id>"。 */
data class SourceKey(val kind: String, val id: String) {
    override fun toString(): String = "$kind:$id"

    companion object {
        fun parse(value: String): SourceKey? {
            val index = value.indexOf(':')
            if (index <= 0) return null
            val kind = value.take(index)
            val id = value.substring(index + 1)
            if (kind != SourceKind.ENTRY.name && kind != SourceKind.LETTER.name) return null
            return SourceKey(kind, id)
        }
    }
}

/** 回顾候选：范围内的一条普通印迹（感受不进入任何投影，见 [ReviewRepository.candidates]）。 */
data class ReviewCandidate(
    val entry: EntryEntity,
    val tagIds: List<String>,
    /** 主媒体数量；动态照片主图＋附属视频合计计 1 */
    val visualCount: Int,
) {
    val key: SourceKey get() = SourceKey(SourceKind.ENTRY.name, entry.id)
}

/** 总结详情：快照行 + 快照媒体 + 由快照派生的统计。 */
data class ReviewDetail(
    val review: ReviewEntity,
    val sources: List<ReviewSourceEntity>,
    val media: List<ReviewSourceMediaEntity>,
    val stats: ReviewStats,
)

/** 更新预览（RV-06）：标出新增、移除、变化项，由用户选择后再保存。 */
data class ReviewUpdatePreview(
    val added: List<ReviewCandidate>,
    val changed: List<Pair<ReviewSourceEntity, ReviewCandidate>>,
    val removed: List<ReviewSourceEntity>,
)

/** 回顾向导草稿（持久化到 content_drafts，进程重启可恢复）。 */
data class ReviewDraft(
    val draftId: String? = null,
    val reviewId: String? = null,
    val periodKind: String,
    val startLocalDate: String,
    val endLocalDateInclusive: String,
    val selectedTagIds: List<String> = emptyList(),
    val title: String = "",
    val userBody: String = "",
    val closingText: String = "",
    val coverSourceKey: String? = null,
    /** 纳入统计的来源键（有序） */
    val includedKeys: List<String> = emptyList(),
    /** 精选片段的来源键（有序，保存用户调整后的顺序） */
    val featuredKeys: List<String> = emptyList(),
)

/** 草稿 payload 的结构化序列化（JSON，随备份导出）。 */
fun ReviewDraft.toJson(): JSONObject = JSONObject()
    .put("reviewId", reviewId ?: "")
    .put("periodKind", periodKind)
    .put("startLocalDate", startLocalDate)
    .put("endLocalDateInclusive", endLocalDateInclusive)
    .put("selectedTagIds", JSONArray().apply { selectedTagIds.forEach { put(it) } })
    .put("title", title).put("userBody", userBody).put("closingText", closingText)
    .put("coverSourceKey", coverSourceKey ?: "")
    .put("includedKeys", JSONArray().apply { includedKeys.forEach { put(it) } })
    .put("featuredKeys", JSONArray().apply { featuredKeys.forEach { put(it) } })

fun reviewDraftFromJson(id: String, payload: String): ReviewDraft {
    val j = JSONObject(payload)
    fun list(key: String): List<String> =
        j.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
    return ReviewDraft(
        draftId = id,
        reviewId = j.optString("reviewId").takeIf { it.isNotBlank() },
        periodKind = j.optString("periodKind").ifBlank { PeriodKind.CUSTOM.name },
        startLocalDate = j.getString("startLocalDate"),
        endLocalDateInclusive = j.getString("endLocalDateInclusive"),
        selectedTagIds = list("selectedTagIds"),
        title = j.optString("title"),
        userBody = j.optString("userBody"),
        closingText = j.optString("closingText"),
        coverSourceKey = j.optString("coverSourceKey").takeIf { it.isNotBlank() },
        includedKeys = list("includedKeys"),
        featuredKeys = list("featuredKeys"),
    )
}

/**
 * 回顾（总结）的持久化、快照与删除传播（RV-01 ~ RV-07）。
 *
 * - 候选只读取**普通、未删除、范围匹配**的印迹；感受不进入投影。
 * - 只有**到期且已确认阅读**的信件可由用户主动加入；提前看过/未拆开的信一律不纳入。
 * - 保存时冻结范围、标签名称、统计、重点原文、片段排序与媒体引用；
 *   来源之后的修改不静默改写快照，只提示可更新（[updatePreview]）。
 */
class ReviewRepository(
    private val database: ImprintDatabase,
    val files: MediaFiles,
    private val core: ImprintRepository,
) {
    private val dao = database.dao()
    private val mailDao = database.mailReviewDao()

    val activeReviews: Flow<List<ReviewEntity>> = mailDao.activeReviews()
    val recycledReviews: Flow<List<ReviewEntity>> = mailDao.recycledReviews()
    val reviewDrafts: Flow<List<ContentDraftEntity>> = mailDao.drafts("REVIEW")

    // ---------- 回顾草稿（进程重启可恢复，RV-03） ----------

    suspend fun saveDraft(draftId: String, draft: ReviewDraft) = core.exclusive {
        mailDao.upsertDraft(
            ContentDraftEntity(
                id = draftId,
                type = "REVIEW",
                targetId = draft.reviewId,
                payload = draft.toJson().toString(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun draft(id: String): ReviewDraft? =
        mailDao.draft(id)?.let { entity -> runCatching { reviewDraftFromJson(entity.id, entity.payload) }.getOrNull() }

    suspend fun deleteDraft(id: String) = core.exclusive { mailDao.deleteDraft(id) }

    // ---------- 候选与统计 ----------

    /** 范围内的普通印迹候选，按事件时间升序、记录时间升序、ID 稳定排序（RV-04）。 */
    suspend fun candidates(range: DateRange, tagIds: Set<String>): List<ReviewCandidate> {
        val entries = dao.candidateEntries(range.start.toString(), range.endInclusive.toString())
        val tagsByEntry = dao.allEntryTagsOnce().groupBy({ it.entryId }, { it.tagId })
        return entries.mapNotNull { entry ->
            val tagIdsOfEntry = tagsByEntry[entry.id].orEmpty()
            if (tagIds.isNotEmpty() && tagIdsOfEntry.none { it in tagIds }) return@mapNotNull null
            ReviewCandidate(entry, tagIdsOfEntry, dao.visuals(entry.id).size)
        }
    }

    /** 候选的事件日期集合（首页回顾邀请用）。 */
    suspend fun entryEventDates(): List<String> =
        dao.activeEntriesOnce().map { it.eventLocalMinute.take(10) }

    /** 可加入回顾的信件：到期**且**已确认阅读、未删除（RV-05）。 */
    suspend fun addableLetters(): List<LetterListRow> =
        mailDao.sealedLettersList().filter { it.deliveryReadAtMs != null && isDueNow(it.deliverAtMs, it.deliveredAtMs) }

    private fun isDueNow(deliverAtMs: Long, deliveredAtMs: Long?): Boolean =
        deliveredAtMs != null || System.currentTimeMillis() >= deliverAtMs

    // ---------- 读取 ----------

    suspend fun review(id: String): ReviewEntity? = mailDao.review(id)

    suspend fun detail(id: String): ReviewDetail? {
        val review = mailDao.review(id) ?: return null
        val sources = mailDao.sources(id)
        val media = mailDao.sourceMedia(id)
        return ReviewDetail(review, sources, media, statsOf(sources, media))
    }

    private fun statsOf(sources: List<ReviewSourceEntity>, media: List<ReviewSourceMediaEntity>): ReviewStats {
        // 视觉媒体数 = 各来源的主媒体数量（动态照片主图＋附属视频合计计 1）
        val visualCounts = media.filter { it.role == "VISUAL" }
            .groupingBy { it.sourceKind to it.sourceId }.eachCount()
        return ReviewStatsCalculator.compute(
            sources.map {
                SourceStatInput(
                    sourceKind = it.sourceKind,
                    sourceId = it.sourceId,
                    eventDate = it.eventDate,
                    included = it.included,
                    visualCount = visualCounts[it.sourceKind to it.sourceId] ?: 0,
                    tagIds = it.tagSnapshot.split(',').filter(String::isNotBlank),
                    visibility = it.visibility,
                )
            },
        )
    }

    /** 相同范围和标签的已有总结（创建时提示「打开已有 / 另建」，不直接覆盖）。 */
    suspend fun findSameScope(periodKind: String, range: DateRange, tagIds: Set<String>): ReviewEntity? {
        val reviews = mailDao.activeReviewsOnce()
        return reviews.firstOrNull { review ->
            review.periodKind == periodKind &&
                review.startLocalDate == range.start.toString() &&
                review.endLocalDateInclusive == range.endInclusive.toString() &&
                review.selectedTagIds.split(',').filter(String::isNotBlank).toSet() == tagIds
        }
    }

    // ---------- 保存 ----------

    /**
     * 保存总结（新建或更新同一个草稿多次提交 → 更新同一个总结 ID）。
     * 冻结来源快照、统计、精选顺序与媒体引用；用户手写字段按传入值保存。
     */
    suspend fun save(draft: ReviewDraft): String = core.exclusive {
        val range = DateRange(ReviewPeriods.parseDate(draft.startLocalDate), ReviewPeriods.parseDate(draft.endLocalDateInclusive))
        val reviewId = draft.reviewId ?: UUID.randomUUID().toString()
        val existing = draft.reviewId?.let { mailDao.review(it) }
        val now = System.currentTimeMillis()
        val keys = draft.includedKeys.mapNotNull(SourceKey::parse).distinct()
        val featuredOrder = draft.featuredKeys.mapIndexedNotNull { index, key -> SourceKey.parse(key)?.let { it to index } }.toMap()

        val snapshots = keys.map { key -> buildSnapshot(reviewId, key, featuredOrder[key], now, range) }
        val tagRows = dao.tagsOnce()
        val tagSnapshotNames = draft.selectedTagIds.joinToString(",") { id -> tagRows.firstOrNull { it.id == id }?.name ?: id }

        database.withTransaction {
            mailDao.upsertReview(
                ReviewEntity(
                    id = reviewId,
                    periodKind = draft.periodKind,
                    startLocalDate = range.start.toString(),
                    endLocalDateInclusive = range.endInclusive.toString(),
                    title = draft.title.trim(),
                    userBody = draft.userBody,
                    closingText = draft.closingText,
                    coverSourceKey = draft.coverSourceKey?.takeIf { key -> keys.any { it.toString() == key } },
                    selectedTagIds = draft.selectedTagIds.joinToString(","),
                    tagNameSnapshot = tagSnapshotNames,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                    deletedAtMs = existing?.deletedAtMs,
                    snapshotRevision = existing?.snapshotRevision ?: 1,
                ),
            )
            mailDao.deleteSources(reviewId)
            mailDao.deleteSourceMedia(reviewId)
            snapshots.forEach { (source, media) ->
                mailDao.upsertSource(source)
                if (media.isNotEmpty()) mailDao.insertSourceMedia(media)
            }
            val ownedPaths = snapshots.flatMap { (_, media) -> media.map { it.relativePath } }
            core.registerMediaOwner(OWNER_REVIEW, reviewId, ownedPaths)
        }
        draft.draftId?.let { mailDao.deleteDraft(it) }
        reviewId
    }

    /** 构建一条来源快照：冻结正文/标签/媒体引用，并记下来源版本指纹。 */
    private suspend fun buildSnapshot(
        reviewId: String,
        key: SourceKey,
        featuredOrder: Int?,
        nowMs: Long,
        range: DateRange,
    ): Pair<ReviewSourceEntity, List<ReviewSourceMediaEntity>> {
        val (updatedAtMs, eventDate, title, body, tagNames, visualCount, media) = when (key.kind) {
            SourceKind.ENTRY.name -> {
                val entry = dao.entry(key.id) ?: error("来源印迹已不存在")
                check(entry.deletedAtMs == null) { "回收站中的印迹不能纳入总结" }
                val visuals = dao.visuals(key.id)
                val audio = dao.audio(key.id)
                val tagIds = dao.tagsOf(key.id)
                val tagRows = dao.tagsOnce()
                val tagNames = tagIds.mapNotNull { id -> tagRows.firstOrNull { it.id == id }?.name }
                val media = visuals.flatMapIndexed { index, v ->
                    listOfNotNull(
                        ReviewSourceMediaEntity(UUID.randomUUID().toString(), reviewId, key.kind, key.id, "VISUAL", index,
                            v.relativePath, v.kind, v.mime, v.byteLength, v.sha256),
                        v.motionVideoPath?.let { path ->
                            ReviewSourceMediaEntity(UUID.randomUUID().toString(), reviewId, key.kind, key.id, "MOTION", index,
                                path, "VIDEO", "video/mp4", v.motionVideoByteLength ?: 0L, v.motionVideoSha256 ?: "")
                        },
                    )
                } + listOfNotNull(
                    audio?.let {
                        ReviewSourceMediaEntity(UUID.randomUUID().toString(), reviewId, key.kind, key.id, "AUDIO", 0,
                            it.relativePath, "AUDIO", it.mime, it.byteLength, it.sha256)
                    },
                )
                Snapshot(entry.updatedAtMs, entry.eventLocalMinute.take(10), "", entry.body, tagNames.joinToString(","), visuals.size, media)
            }
            SourceKind.LETTER.name -> {
                val letter = mailDao.letter(key.id) ?: error("来源信件已不存在")
                val entry = dao.entry(key.id) ?: error("来源信件已不存在")
                check(entry.deletedAtMs == null) { "回收站中的信件不能纳入总结" }
                check(letter.deliveryReadAtMs != null) { "只有到期且已读的信件可以加入总结" }
                val visuals = dao.visuals(key.id)
                val audio = dao.audio(key.id)
                val media = visuals.flatMapIndexed { index, v ->
                    listOf(ReviewSourceMediaEntity(UUID.randomUUID().toString(), reviewId, key.kind, key.id, "VISUAL", index,
                        v.relativePath, v.kind, v.mime, v.byteLength, v.sha256))
                } + listOfNotNull(
                    audio?.let {
                        ReviewSourceMediaEntity(UUID.randomUUID().toString(), reviewId, key.kind, key.id, "AUDIO", 0,
                            it.relativePath, "AUDIO", it.mime, it.byteLength, it.sha256)
                    },
                )
                Snapshot(letter.deliverAtMs, letter.deliverLocalMinute.take(10), LetterTitleOf(letter.title), entry.body, "", visuals.size, media)
            }
            else -> error("未知来源类型")
        }
        val source = ReviewSourceEntity(
            reviewId = reviewId,
            sourceKind = key.kind,
            sourceId = key.id,
            sourceUpdatedAtMs = updatedAtMs,
            contentHash = contentHashOf(body, media),
            eventDate = eventDate,
            included = true,
            featured = featuredOrder != null,
            featuredOrder = featuredOrder ?: 0,
            titleSnapshot = title,
            bodySnapshot = body,
            tagSnapshot = tagNames,
            visibility = SourceVisibility.VISIBLE.name,
            createdAtMs = nowMs,
        )
        return source to media
    }

    private fun LetterTitleOf(title: String): String = com.beyondguo.imprints.mail.LetterRules.displayTitle(title)

    private data class Snapshot(
        val updatedAtMs: Long,
        val eventDate: String,
        val title: String,
        val body: String,
        val tagNames: String,
        val visualCount: Int,
        val media: List<ReviewSourceMediaEntity>,
    )

    private fun contentHashOf(body: String, media: List<ReviewSourceMediaEntity>): String {
        val canonical = buildString {
            append(body)
            media.sortedWith(compareBy({ it.sourceId }, { it.role }, { it.ordinal })).forEach {
                append('|').append(it.relativePath).append('#').append(it.sha256)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------- 更新预览（RV-06） ----------

    /**
     * 生成更新预览：新增（范围内但现在没进快照的）、变化（正文/媒体指纹不同）、移除（快照里但已不可见）。
     * 用户确认前不改任何快照。
     */
    suspend fun updatePreview(reviewId: String, range: DateRange, tagIds: Set<String>): ReviewUpdatePreview {
        val sources = mailDao.sources(reviewId)
        val candidates = candidates(range, tagIds)
        val byKey = candidates.associateBy { it.toString() }
        val snapshotKeys = sources.filter { it.visibility == SourceVisibility.VISIBLE.name }.map { it.toString() }.toSet()

        val added = candidates.filter { it.toString() !in snapshotKeys }
        val changed = sources.mapNotNull { source ->
            val candidate = byKey[source.toString()] ?: return@mapNotNull null
            if (source.visibility != SourceVisibility.VISIBLE.name) return@mapNotNull null
            val currentHash = currentHashOf(candidate)
            if (currentHash != source.contentHash) source to candidate else null
        }
        val removed = sources.filter { source ->
            source.visibility != SourceVisibility.VISIBLE.name ||
                (source.sourceKind == SourceKind.ENTRY.name && byKey[source.toString()] == null)
        }
        return ReviewUpdatePreview(added, changed, removed)
    }

    private suspend fun currentHashOf(candidate: ReviewCandidate): String {
        val media = mutableListOf<ReviewSourceMediaEntity>()
        val visuals = dao.visuals(candidate.entry.id)
        val audio = dao.audio(candidate.entry.id)
        visuals.forEachIndexed { index, v ->
            media += ReviewSourceMediaEntity("", "", "", "", "VISUAL", index, v.relativePath, v.kind, v.mime, v.byteLength, v.sha256)
            v.motionVideoPath?.let {
                media += ReviewSourceMediaEntity("", "", "", "", "MOTION", index, it, "VIDEO", "video/mp4", v.motionVideoByteLength ?: 0L, v.motionVideoSha256 ?: "")
            }
        }
        audio?.let { media += ReviewSourceMediaEntity("", "", "", "", "AUDIO", 0, it.relativePath, "AUDIO", it.mime, it.byteLength, it.sha256) }
        return contentHashOf(candidate.entry.body, media)
    }

    /**
     * 应用用户确认的更新：
     * - 新增项按选择补进快照；
     * - 变化项按选择刷新快照**内容**（保留 included/featured 与用户排序，不碰手写字段）；
     * - 移除项按选择从快照删除。
     */
    suspend fun applyUpdate(
        reviewId: String,
        preview: ReviewUpdatePreview,
        addKeys: Set<String>,
        refreshKeys: Set<String>,
        removeKeys: Set<String>,
        range: DateRange,
    ): Unit = core.exclusive {
        val review = mailDao.review(reviewId) ?: error("总结已不存在")
        val now = System.currentTimeMillis()
        val featuredOrder = mailDao.sources(reviewId).filter { it.featured }.associate { it.toString() to it.featuredOrder }
        database.withTransaction {
            preview.added.filter { it.toString() in addKeys }.forEach { candidate ->
                val (source, media) = buildSnapshot(reviewId, candidate.key, featuredOrder[candidate.key.toString()], now, range)
                mailDao.upsertSource(source)
                if (media.isNotEmpty()) mailDao.insertSourceMedia(media)
            }
            preview.changed.filter { (source, _) -> source.toString() in refreshKeys }.forEach { (source, candidate) ->
                val (fresh, media) = buildSnapshot(reviewId, candidate.key, null, now, range)
                // 只更新内容快照与指纹，保留用户的选择与排序
                mailDao.upsertSource(
                    fresh.copy(
                        included = source.included,
                        featured = source.featured,
                        featuredOrder = source.featuredOrder,
                        visibility = source.visibility,
                        createdAtMs = source.createdAtMs,
                    ),
                )
                mailDao.deleteSourceMediaIn(reviewId, candidate.key.kind, candidate.key.id)
                if (media.isNotEmpty()) mailDao.insertSourceMedia(media)
            }
            preview.removed.filter { it.toString() in removeKeys }.forEach { source ->
                mailDao.deleteSourceMediaIn(reviewId, source.sourceKind, source.sourceId)
                mailDao.upsertSource(source.copy(visibility = SourceVisibility.PURGED.name, titleSnapshot = "", bodySnapshot = "", tagSnapshot = "", contentHash = ""))
            }
            val ownedPaths = mailDao.sourceMedia(reviewId).map { it.relativePath }
            core.registerMediaOwner(OWNER_REVIEW, reviewId, ownedPaths)
            mailDao.upsertReview(review.copy(updatedAtMs = now, snapshotRevision = review.snapshotRevision + 1))
        }
        Unit
    }

    /** 主动把一封已读信加入指定总结（ML-05/RV-05）；逐条、显式来源标识。 */
    suspend fun addLetterSource(reviewId: String, letterEntryId: String): Unit = core.exclusive {
        val review = mailDao.review(reviewId) ?: error("总结已不存在")
        val range = DateRange(ReviewPeriods.parseDate(review.startLocalDate), ReviewPeriods.parseDate(review.endLocalDateInclusive))
        val now = System.currentTimeMillis()
        val existing = mailDao.sourcesOf(SourceKind.LETTER.name, letterEntryId).any { it.reviewId == reviewId }
        if (existing) return@exclusive
        val (source, media) = buildSnapshot(reviewId, SourceKey(SourceKind.LETTER.name, letterEntryId), null, now, range)
        database.withTransaction {
            mailDao.upsertSource(source)
            if (media.isNotEmpty()) mailDao.insertSourceMedia(media)
            core.registerMediaOwner(OWNER_REVIEW, reviewId, mailDao.sourceMedia(reviewId).map { it.relativePath })
            mailDao.upsertReview(review.copy(updatedAtMs = now))
        }
        Unit
    }

    // ---------- 删除与回收站（RV-07） ----------

    suspend fun moveToRecycle(id: String) = core.exclusive {
        val review = mailDao.review(id) ?: return@exclusive
        if (review.deletedAtMs == null) mailDao.upsertReview(review.copy(deletedAtMs = System.currentTimeMillis()))
    }

    suspend fun restore(id: String) = core.exclusive {
        val review = mailDao.review(id) ?: error("总结已不存在")
        check(review.deletedAtMs != null) { "总结不在回收站" }
        check(EntryRules.isRestorable(review.deletedAtMs, System.currentTimeMillis())) { "已超过 7 天保留期，无法恢复" }
        // 恢复时再次检查来源可见性：来源已彻底删除的内容不能借恢复总结复活
        mailDao.sources(id).forEach { source ->
            if (source.visibility == SourceVisibility.PURGED.name) {
                mailDao.upsertSource(source.copy(titleSnapshot = "", bodySnapshot = "", tagSnapshot = "", contentHash = ""))
                mailDao.deleteSourceMediaOf(source.sourceKind, source.sourceId)
            }
        }
        mailDao.upsertReview(review.copy(deletedAtMs = null))
    }

    /** 彻底删除总结：只释放自己的快照与媒体引用，不删来源印迹或信件。 */
    suspend fun deletePermanently(id: String) = core.exclusive {
        val review = mailDao.review(id) ?: return@exclusive
        val paths = mailDao.sourceMedia(id).map { it.relativePath }
        database.withTransaction {
            mailDao.deleteSources(id)
            mailDao.deleteSourceMedia(id)
            mailDao.deleteMediaRefs(OWNER_REVIEW, id)
            mailDao.deleteReview(id)
        }
        core.deleteFilesIfUnreferenced(paths)
        Unit
    }

    // ---------- 回顾邀请（RV-01） ----------

    /** 首页邀请：最近一个已结束且有普通印迹的周期，优先年、月、周；可被用户关闭。 */
    suspend fun invitation(today: LocalDate): Pair<String, DateRange>? {
        val dates = entryEventDates()
        val closed = mailDao.invitationStates().associateBy { "${it.periodKind}|${it.startLocalDate}|${it.endLocalDateInclusive}" }
        return ReviewPeriods.invitationRange(today, dates) { kind, range ->
            closed.containsKey("${kind}|${range.start}|${range.endInclusive}")
        }
    }

    /** 关闭邀请：只记偏好，不删记录、不禁止手动创建。 */
    suspend fun closeInvitation(kind: String, range: DateRange) = core.exclusive {
        mailDao.upsertInvitationState(
            ReviewInvitationStateEntity(kind, range.start.toString(), range.endInclusive.toString(), System.currentTimeMillis()),
        )
    }

    /** 该周期同范围且无标签筛选的总结是否已保存（保存后隐藏对应邀请）。 */
    suspend fun hasFullScopeReview(kind: String, range: DateRange): Boolean =
        mailDao.activeReviewsOnce().any { review ->
            review.periodKind == kind &&
                review.startLocalDate == range.start.toString() &&
                review.endLocalDateInclusive == range.endInclusive.toString() &&
                review.selectedTagIds.isBlank()
        }
}
