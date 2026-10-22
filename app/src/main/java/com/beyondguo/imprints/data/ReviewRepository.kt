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
    /** 首张主图的相对路径与类型，用于回顾里的缩略图预览（可为空） */
    val coverPath: String? = null,
    val coverKind: String? = null,
    /** 完整详情（位置 / 媒体 / 音频 / 标签），供回顾面板复用时间线的条目渲染 */
    val detail: EntryDetail? = null,
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
            val visuals = dao.visuals(entry.id)
            ReviewCandidate(
                entry, tagIdsOfEntry, visuals.size,
                coverPath = visuals.firstOrNull()?.relativePath,
                coverKind = visuals.firstOrNull()?.kind,
                detail = core.detail(entry.id),
            )
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
        // V0.2 动态范围：总结的成员**不是存下来的清单**，而是按「起止日期 + 可选标签条件」现查。
        // 所以新增/补记符合条件的印迹会自动出现，改了事件日期或标签会自动移出，
        // 也就没有「失效／范围外关联列表」和「移除／替换」这些流程了。
        val range = DateRange(
            ReviewPeriods.parseDate(review.startLocalDate),
            ReviewPeriods.parseDate(review.endLocalDateInclusive),
        )
        val tagIds = review.selectedTagIds.split(',').filter(String::isNotBlank).toSet()
        val sources = candidates(range, tagIds).mapIndexed { index, candidate -> rowOf(review, candidate, index) }
        val media = sources.flatMap { mediaOf(it) }
        return ReviewDetail(review, sources, media, statsOf(sources, media))
    }

    /** 把查询结果包装成「来源行」给 UI 用（纯视图对象，不落库）。 */
    private suspend fun rowOf(review: ReviewEntity, candidate: ReviewCandidate, order: Int): ReviewSourceEntity {
        val tagRows = dao.tagsOnce()
        val names = candidate.tagIds.mapNotNull { id -> tagRows.firstOrNull { it.id == id }?.name }
        return ReviewSourceEntity(
            reviewId = review.id,
            sourceKind = SourceKind.ENTRY.name,
            sourceId = candidate.entry.id,
            sourceUpdatedAtMs = candidate.entry.updatedAtMs,
            eventDate = candidate.entry.eventLocalMinute.take(10),
            featuredOrder = order,
            bodySnapshot = candidate.entry.body,
            tagSnapshot = names.joinToString(","),
            visibility = SourceVisibility.VISIBLE.name,
            createdAtMs = review.createdAtMs,
        )
    }

    /** 用原印迹当前数据填充来源行；来源已进回收站/被彻底删除时清空内容，只留占位。 */
    private suspend fun enrich(row: ReviewSourceEntity): ReviewSourceEntity {
        val entry = dao.entry(row.sourceId)
            ?: return blanked(row, SourceVisibility.PURGED)
        if (entry.deletedAtMs != null) return blanked(row, SourceVisibility.RECYCLED)
        val tagRows = dao.tagsOnce()
        val names = dao.tagsOf(entry.id).mapNotNull { id -> tagRows.firstOrNull { it.id == id }?.name }
        return row.copy(
            sourceUpdatedAtMs = entry.updatedAtMs,
            eventDate = entry.eventLocalMinute.take(10),
            bodySnapshot = entry.body,
            tagSnapshot = names.joinToString(","),
            visibility = SourceVisibility.VISIBLE.name,
        )
    }

    private fun blanked(row: ReviewSourceEntity, visibility: SourceVisibility) = row.copy(
        titleSnapshot = "", bodySnapshot = "", tagSnapshot = "", eventDate = "",
        visibility = visibility.name,
    )

    /**
     * 现读原印迹的媒体，不从 `review_source_media` 取（那张表 V0.2 起不再写入）。
     * id 用确定性拼接而不是随机 UUID —— UI 会拿它做 key，每次变会导致无谓重组。
     */
    private suspend fun mediaOf(row: ReviewSourceEntity): List<ReviewSourceMediaEntity> {
        if (row.visibility != SourceVisibility.VISIBLE.name) return emptyList()
        fun id(role: String, ordinal: Int) = "${row.reviewId}:${row.sourceId}:$role:$ordinal"
        val visuals = dao.visuals(row.sourceId)
        val audio = dao.audio(row.sourceId)
        return visuals.flatMapIndexed { index, v ->
            listOfNotNull(
                ReviewSourceMediaEntity(
                    id("VISUAL", index), row.reviewId, row.sourceKind, row.sourceId, "VISUAL", index,
                    v.relativePath, v.kind, v.mime, v.byteLength, v.sha256,
                ),
                v.motionVideoPath?.let { path ->
                    ReviewSourceMediaEntity(
                        id("MOTION", index), row.reviewId, row.sourceKind, row.sourceId, "MOTION", index,
                        path, "VIDEO", "video/mp4", v.motionVideoByteLength ?: 0L, v.motionVideoSha256 ?: "",
                    )
                },
            )
        } + listOfNotNull(
            audio?.let {
                ReviewSourceMediaEntity(
                    id("AUDIO", 0), row.reviewId, row.sourceKind, row.sourceId, "AUDIO", 0,
                    it.relativePath, "AUDIO", it.mime, it.byteLength, it.sha256,
                )
            },
        )
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
                    // 封面只保留一个可失效的媒体指针，不校验成员资格：指向的记录/媒体失效时
                    // 详情页降级为默认封面（RV-06）
                    coverSourceKey = draft.coverSourceKey,
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
            // V0.2 动态范围：成员由「起止日期 + 标签条件」现查，不保存来源 ID 清单或顺序，
            // 所以这里不写 review_sources / review_source_media（上面的 delete 只清历史残留）。
        }
        draft.draftId?.let { mailDao.deleteDraft(it) }
        reviewId
    }

    /**
     * 校验来源资格并生成关联行。
     *
     * V0.2：关联只存稳定 ID 与顺序，**不复制正文/标签/媒体** —— 所以这里只判断"能不能关联"。
     * 邮件（LETTER）自 V0.2 起不再是回顾素材，直接拒绝。
     */
    private suspend fun buildSourceRow(
        reviewId: String,
        key: SourceKey,
        order: Int,
        nowMs: Long,
    ): ReviewSourceEntity {
        when (key.kind) {
            SourceKind.ENTRY.name -> {
                val entry = dao.entry(key.id) ?: error("来源印迹已不存在")
                check(entry.deletedAtMs == null) { "回收站中的印迹不能纳入总结" }
            }
            else -> error("只有普通印迹可以作为回顾来源")
        }
        return ReviewSourceEntity(
            reviewId = reviewId,
            sourceKind = key.kind,
            sourceId = key.id,
            featuredOrder = order,
            createdAtMs = nowMs,
        )
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
        var nextOrder = (mailDao.sources(reviewId).maxOfOrNull { it.featuredOrder } ?: -1) + 1
        database.withTransaction {
            // 新增：只写关联关系，不复制内容
            preview.added.filter { it.toString() in addKeys }.forEach { candidate ->
                mailDao.upsertSource(buildSourceRow(reviewId, candidate.key, nextOrder++, now))
            }
            // 变化的来源不需要任何处理：内容本来就是现读原印迹的（V0.2 取消「刷新预览」）
            // 移除：直接删掉关系行，原印迹不动
            preview.removed.filter { it.toString() in removeKeys }.forEach { source ->
                mailDao.deleteSourceMediaIn(reviewId, source.sourceKind, source.sourceId)
                mailDao.deleteSourceRow(reviewId, source.sourceKind, source.sourceId)
            }
            mailDao.upsertReview(review.copy(updatedAtMs = now, snapshotRevision = review.snapshotRevision + 1))
        }
        Unit
    }

    /**
     * 已废止（V0.2）。
     *
     * 邮件不再作为回顾素材：规格明确「全部邮件均排除在回顾素材之外」，
     * 也不再建立总结与邮件的持久关联。保留这个函数只是为了给旧调用方一个明确的错误，
     * 而不是静默写入一条不该存在的来源。UI 侧入口会在下一轮一并移除。
     */
    suspend fun addLetterSource(reviewId: String, letterEntryId: String): Unit =
        error("邮件不能作为回顾素材：请在「回顾这段时光」里按日期范围创建普通回顾")

    /** 主动把一封已读信加入指定总结 —— 旧实现，保留待删。 */
    private suspend fun addLetterSourceLegacy(reviewId: String, letterEntryId: String): Unit = core.exclusive {
        val review = mailDao.review(reviewId) ?: error("总结已不存在")
        val now = System.currentTimeMillis()
        val existing = mailDao.sourcesOf(SourceKind.LETTER.name, letterEntryId).any { it.reviewId == reviewId }
        if (existing) return@exclusive
        val source = buildSourceRow(reviewId, SourceKey(SourceKind.LETTER.name, letterEntryId), 0, now)
        database.withTransaction {
            mailDao.upsertSource(source)
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
