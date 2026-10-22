package com.beyondguo.imprints.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 信件、总结、草稿、媒体引用与邀请偏好的读写入口。
 *
 * 隔离原则：普通印迹的查询在 [ImprintDao] 里就带 `NOT IN (SELECT entryId FROM future_letters)`
 * 排除条件；本 DAO 提供语义明确的信封/正文/回顾候选读取接口，调用方不得绕过这里直接拼表查。
 */
@Dao
interface MailReviewDao {
    // ---------- 信件 ----------

    @Query("SELECT * FROM future_letters WHERE entryId = :id LIMIT 1")
    suspend fun letter(id: String): FutureLetterEntity?

    @Query("SELECT * FROM future_letters")
    suspend fun allLetters(): List<FutureLetterEntity>

    /** 邮箱/首页用：封面投影，只带标题与日期，不解码正文与媒体。 */
    @Query(
        "SELECT e.id AS entryId, l.title, l.deliverAtMs, l.deliverLocalMinute, l.deliverZoneId, " +
            "l.deliveredAtMs, l.firstOpenedAtMs, l.deliveryReadAtMs, l.notifyEnabled, l.scheduleRevision, " +
            "e.createdAtMs, e.updatedAtMs, e.deletedAtMs " +
            "FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NULL " +
            "ORDER BY l.deliverAtMs ASC, l.entryId ASC",
    )
    fun sealedLetters(): Flow<List<LetterListRow>>

    /** 回收站里的信件（7 天内）。 */
    @Query(
        "SELECT e.id AS entryId, l.title, l.deliverAtMs, l.deliverLocalMinute, l.deliverZoneId, " +
            "l.deliveredAtMs, l.firstOpenedAtMs, l.deliveryReadAtMs, l.notifyEnabled, l.scheduleRevision, " +
            "e.createdAtMs, e.updatedAtMs, e.deletedAtMs " +
            "FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NOT NULL " +
            "ORDER BY e.deletedAtMs DESC",
    )
    fun recycledLetters(): Flow<List<LetterListRow>>

    /** [sealedLetters] 的一次性版本（拆信流程/信封详情按 id 查）。 */
    @Query(
        "SELECT e.id AS entryId, l.title, l.deliverAtMs, l.deliverLocalMinute, l.deliverZoneId, " +
            "l.deliveredAtMs, l.firstOpenedAtMs, l.deliveryReadAtMs, l.notifyEnabled, l.scheduleRevision, " +
            "e.createdAtMs, e.updatedAtMs, e.deletedAtMs " +
            "FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NULL " +
            "ORDER BY l.deliverAtMs ASC, l.entryId ASC",
    )
    suspend fun sealedLettersList(): List<LetterListRow>

    /** [recycledLetters] 的一次性版本。 */
    @Query(
        "SELECT e.id AS entryId, l.title, l.deliverAtMs, l.deliverLocalMinute, l.deliverZoneId, " +
            "l.deliveredAtMs, l.firstOpenedAtMs, l.deliveryReadAtMs, l.notifyEnabled, l.scheduleRevision, " +
            "e.createdAtMs, e.updatedAtMs, e.deletedAtMs " +
            "FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NOT NULL " +
            "ORDER BY e.deletedAtMs DESC",
    )
    suspend fun recycledLettersList(): List<LetterListRow>

    /** 已确认到期（deliveredAtMs 非空或到达送达时间）且未删除的信件。 */
    @Query(
        "SELECT l.* FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NULL AND (l.deliveredAtMs IS NOT NULL OR l.deliverAtMs <= :now)",
    )
    suspend fun dueLetters(now: Long): List<FutureLetterEntity>

    /** 尚未到期、需要注册调度的信件。 */
    @Query(
        "SELECT l.* FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NULL AND l.deliveredAtMs IS NULL AND l.deliverAtMs > :now",
    )
    suspend fun upcomingLetters(now: Long): List<FutureLetterEntity>

    /** 调度对账用：一封信的全部调度版本记录。 */
    @Query("SELECT * FROM letter_notification_events WHERE entryId = :id")
    suspend fun notificationEvents(id: String): List<LetterNotificationEventEntity>

    /**
     * 封存管理页专用：**只投影日期**，不含标题／正文／附件／firstOpenedAtMs。
     * 返回未删除且尚未确认到期的行；是否「未到期」由调用方按 now 再过滤，
     * 这样 Flow 不绑定某个时刻的快照，管理页跨过到期时间也能靠重新读库得到正确结果。
     */
    @Query(
        "SELECT l.entryId AS entryId, e.createdAtMs AS sealedAtMs, l.deliverAtMs AS deliverAtMs, " +
            "l.deliverLocalMinute AS deliverLocalMinute, l.deliverZoneId AS deliverZoneId, " +
            "l.scheduleRevision AS scheduleRevision " +
            "FROM future_letters l JOIN entries e ON e.id = l.entryId " +
            "WHERE e.deletedAtMs IS NULL AND l.deliveredAtMs IS NULL " +
            "ORDER BY l.deliverAtMs ASC, l.entryId ASC",
    )
    fun sealedLetterMetas(): Flow<List<SealedLetterMeta>>

    @Upsert suspend fun upsertLetter(value: FutureLetterEntity)

    @Query("DELETE FROM future_letters WHERE entryId = :id")
    suspend fun deleteLetter(id: String)

    @Upsert suspend fun upsertNotificationEvent(value: LetterNotificationEventEntity)

    @Query("DELETE FROM letter_notification_events WHERE entryId = :id")
    suspend fun deleteNotificationEvents(id: String)

    @Query("SELECT COUNT(*) > 0 FROM future_letters WHERE entryId = :id")
    suspend fun isLetter(id: String): Boolean

    // ---------- 草稿 ----------

    @Query("SELECT * FROM content_drafts WHERE type = :type ORDER BY updatedAtMs DESC")
    fun drafts(type: String): Flow<List<ContentDraftEntity>>

    /**
     * 草稿箱专用：只返回**独立未封存草稿**（`targetId IS NULL`）。
     * 关联已封存信的旧编辑草稿不得作为续写入口，因此不能出现在草稿箱里。
     */
    @Query("SELECT * FROM content_drafts WHERE type = :type AND targetId IS NULL ORDER BY updatedAtMs DESC")
    fun standaloneDrafts(type: String): Flow<List<ContentDraftEntity>>

    /** V0.1 遗留：关联已封存信的编辑草稿（V0.2 起无编辑功能，可在启动时清理）。 */
    @Query("SELECT * FROM content_drafts WHERE type = :type AND targetId IS NOT NULL")
    suspend fun draftsWithTarget(type: String): List<ContentDraftEntity>

    @Query("SELECT * FROM content_drafts WHERE id = :id LIMIT 1")
    suspend fun draft(id: String): ContentDraftEntity?

    @Query("SELECT * FROM content_drafts")
    suspend fun allDrafts(): List<ContentDraftEntity>

    @Upsert suspend fun upsertDraft(value: ContentDraftEntity)

    @Query("DELETE FROM content_drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)

    // ---------- 总结 ----------

    @Query("SELECT * FROM reviews WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
    fun activeReviews(): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM reviews WHERE deletedAtMs IS NOT NULL ORDER BY deletedAtMs DESC")
    fun recycledReviews(): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM reviews WHERE id = :id LIMIT 1")
    suspend fun review(id: String): ReviewEntity?

    @Query("SELECT * FROM reviews")
    suspend fun allReviews(): List<ReviewEntity>

    @Query("SELECT * FROM reviews WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
    suspend fun activeReviewsOnce(): List<ReviewEntity>

    @Query("DELETE FROM reviews WHERE id = :id")
    suspend fun deleteReview(id: String)

    @Query("SELECT * FROM review_sources WHERE reviewId = :reviewId ORDER BY eventDate, createdAtMs")
    suspend fun sources(reviewId: String): List<ReviewSourceEntity>

    @Query("SELECT * FROM review_sources WHERE reviewId = :reviewId AND featured = 1 ORDER BY featuredOrder")
    suspend fun featuredSources(reviewId: String): List<ReviewSourceEntity>

    /** 删除传播用：某个来源被哪些总结引用。 */
    @Query("SELECT * FROM review_sources WHERE sourceKind = :kind AND sourceId = :id")
    suspend fun sourcesOf(kind: String, id: String): List<ReviewSourceEntity>

    /** 删除传播用：某个来源在快照里引用的媒体。 */
    @Query("SELECT * FROM review_source_media WHERE sourceKind = :kind AND sourceId = :id")
    suspend fun sourceMediaOf(kind: String, id: String): List<ReviewSourceMediaEntity>

    @Query("DELETE FROM review_source_media WHERE sourceKind = :kind AND sourceId = :id")
    suspend fun deleteSourceMediaOf(kind: String, id: String)

    @Query("DELETE FROM review_source_media WHERE reviewId = :reviewId AND sourceKind = :kind AND sourceId = :id")
    suspend fun deleteSourceMediaIn(reviewId: String, kind: String, id: String)

    @Query("SELECT * FROM review_source_media WHERE reviewId = :reviewId ORDER BY sourceKind, sourceId, role, ordinal")
    suspend fun sourceMedia(reviewId: String): List<ReviewSourceMediaEntity>

    @Query("SELECT * FROM review_source_media")
    suspend fun allSourceMedia(): List<ReviewSourceMediaEntity>

    @Query("SELECT * FROM review_sources")
    suspend fun allSources(): List<ReviewSourceEntity>

    @Upsert suspend fun upsertReview(value: ReviewEntity)

    @Upsert suspend fun upsertSource(value: ReviewSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSources(values: List<ReviewSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSourceMedia(values: List<ReviewSourceMediaEntity>)

    @Query("DELETE FROM review_sources WHERE reviewId = :reviewId")
    suspend fun deleteSources(reviewId: String)

    /** 解除单条关联（用户移除某个来源）。只删关系行，原印迹不受影响。 */
    @Query("DELETE FROM review_sources WHERE reviewId = :reviewId AND sourceKind = :kind AND sourceId = :sourceId")
    suspend fun deleteSourceRow(reviewId: String, kind: String, sourceId: String)

    @Query("DELETE FROM review_source_media WHERE reviewId = :reviewId")
    suspend fun deleteSourceMedia(reviewId: String)

    // ---------- 媒体引用（所有权） ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaRefs(values: List<MediaReferenceEntity>)

    @Query("DELETE FROM media_references WHERE ownerKind = :kind AND ownerId = :id")
    suspend fun deleteMediaRefs(kind: String, id: String)

    @Query("DELETE FROM media_references WHERE ownerKind = :kind AND ownerId = :id AND relativePath = :path")
    suspend fun deleteMediaRef(kind: String, id: String, path: String)

    @Query("SELECT DISTINCT relativePath FROM media_references")
    suspend fun mediaRefPaths(): List<String>

    @Query("SELECT relativePath FROM media_references WHERE ownerKind = :kind AND ownerId = :id")
    suspend fun mediaRefPathsOwnedBy(kind: String, id: String): List<String>

    @Query("SELECT DISTINCT relativePath FROM review_source_media")
    suspend fun reviewMediaPaths(): List<String>

    // ---------- 回顾邀请偏好 ----------

    @Query("SELECT * FROM review_invitation_state")
    suspend fun invitationStates(): List<ReviewInvitationStateEntity>

    @Upsert suspend fun upsertInvitationState(value: ReviewInvitationStateEntity)
}
