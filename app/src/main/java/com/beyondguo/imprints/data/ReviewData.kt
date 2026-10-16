package com.beyondguo.imprints.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 内容对象类型：普通印迹 / 未来信 / 总结（作品）。草稿不属于已保存内容。 */
enum class ContentKind { ENTRY, LETTER, REVIEW }

/** 信件生命周期（ML-03）：草稿 → 封存待送达 → 到期未读 → 到期已读。 */
enum class LetterState { DRAFT, WAITING, DUE_UNREAD, DUE_READ }

/** 回顾周期类型（RV-02）。 */
enum class PeriodKind { WEEK, MONTH, YEAR, CUSTOM }

/** 回顾来源对象类型：普通印迹或已到期且已读的信件。 */
enum class SourceKind { ENTRY, LETTER }

/** 快照里来源的当前可见性；封面与统计一律由它派生，不直接信快照原文。 */
enum class SourceVisibility { VISIBLE, RECYCLED, PURGED }

/**
 * 总结（用户保存的回顾作品）。**不进 entries 表**——避免被再次当作回顾素材形成递归统计。
 *
 * `selectedTagIds` / `tagNameSnapshot` 用逗号分隔的 id / 名称快照成对保存：
 * 保存后即使标签被改名或停用，回看时仍是当时的名字。
 */
@Entity(
    tableName = "reviews",
    indices = [Index("deletedAtMs"), Index("updatedAtMs")],
)
data class ReviewEntity(
    @PrimaryKey val id: String,
    val periodKind: String,
    /** 起始日期，ISO yyyy-MM-dd（含当日） */
    val startLocalDate: String,
    /** 结束日期，ISO yyyy-MM-dd（含当日） */
    val endLocalDateInclusive: String,
    val title: String,
    val userBody: String,
    val closingText: String,
    /** 封面来源键，格式 "ENTRY:<id>" / "LETTER:<id>"；null = 无封面 */
    val coverSourceKey: String? = null,
    val selectedTagIds: String = "",
    val tagNameSnapshot: String = "",
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
    /** 快照版本：来源更新预览保存后递增 */
    val snapshotRevision: Int = 1,
)

/**
 * 总结的来源快照行。**所有纳入统计的来源都有关系行**，不只是精选片段
 * （`included` = 纳入统计，`featured` = 展示为重点片段，两者独立）。
 *
 * 正文/标签是保存时冻结的快照；来源被修改不会静默重写快照，只提示可更新。
 * `visibility` 由来源的删除状态派生：来源进回收站 → RECYCLED（内容立即隐藏但行保留），
 * 彻底删除 → PURGED（快照正文与媒体引用清空，只留无内容占位）。
 */
@Entity(
    tableName = "review_sources",
    primaryKeys = ["reviewId", "sourceKind", "sourceId"],
    foreignKeys = [ForeignKey(entity = ReviewEntity::class, parentColumns = ["id"], childColumns = ["reviewId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("reviewId"), Index("sourceId")],
)
data class ReviewSourceEntity(
    val reviewId: String,
    val sourceKind: String,
    val sourceId: String,
    /** 来源版本指纹（updatedAtMs），用于「来源记录有变化」提示 */
    val sourceUpdatedAtMs: Long,
    /** 来源内容摘要指纹，用于区分「只是改了时间戳」与「正文/媒体真的变了」 */
    val contentHash: String,
    /** 来源事件日期（ISO yyyy-MM-dd）；信件取约定送达日期 */
    val eventDate: String,
    val included: Boolean = true,
    val featured: Boolean = false,
    val featuredOrder: Int = 0,
    val titleSnapshot: String = "",
    val bodySnapshot: String = "",
    val tagSnapshot: String = "",
    val visibility: String = SourceVisibility.VISIBLE.name,
    val createdAtMs: Long,
)

/**
 * 来源快照引用的私有媒体对象（主图 / 动态附属视频 / 音频）。
 * 只存应用私有目录的相对路径（UUID 文件名），不存可失效的系统 URI；
 * 与 [MediaReferenceEntity] 一起保证「替换附件后旧快照仍可读」。
 */
@Entity(
    tableName = "review_source_media",
    foreignKeys = [ForeignKey(
        entity = ReviewSourceEntity::class,
        parentColumns = ["reviewId", "sourceKind", "sourceId"],
        childColumns = ["reviewId", "sourceKind", "sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("reviewId"), Index("reviewId", "sourceKind", "sourceId"), Index("relativePath")],
)
data class ReviewSourceMediaEntity(
    @PrimaryKey val id: String,
    val reviewId: String,
    val sourceKind: String,
    val sourceId: String,
    /** VISUAL / MOTION / AUDIO */
    val role: String,
    val ordinal: Int,
    val relativePath: String,
    val kind: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
)

/**
 * 编辑器草稿（信件 LETTER / 回顾 REVIEW）。
 * payload 是结构化 JSON；草稿的附件所有权记在 [MediaReferenceEntity]（ownerKind = DRAFT）。
 * 草稿不触发送达任务、不计入未读数、不混入任何统计。
 */
@Entity(
    tableName = "content_drafts",
    indices = [Index("type"), Index("updatedAtMs")],
)
data class ContentDraftEntity(
    @PrimaryKey val id: String,
    val type: String,
    /** 编辑目标（已存在的信/总结）；新建草稿为 null */
    val targetId: String? = null,
    val payload: String,
    val updatedAtMs: Long,
)

/**
 * 媒体文件所有权：记录 entries、信件、快照、草稿各自占有哪些文件。
 * 物理文件只有在**没有任何有效引用**时才删除——不能只依赖 entryId 清理，
 * 否则替换附件或删除来源会把总结快照还在用的文件一并删掉。
 */
@Entity(
    tableName = "media_references",
    primaryKeys = ["ownerKind", "ownerId", "relativePath"],
    indices = [Index("relativePath")],
)
data class MediaReferenceEntity(
    /** ENTRY / LETTER / REVIEW / DRAFT */
    val ownerKind: String,
    val ownerId: String,
    val relativePath: String,
)

/**
 * 回顾邀请的关闭偏好（首页「上周留下了 N 条印迹，回头看看」）。
 * 关闭只记录偏好，不删除记录、不禁止手动创建；不保存生成的正文。
 */
@Entity(
    tableName = "review_invitation_state",
    primaryKeys = ["periodKind", "startLocalDate", "endLocalDateInclusive"],
)
data class ReviewInvitationStateEntity(
    val periodKind: String,
    val startLocalDate: String,
    val endLocalDateInclusive: String,
    val closedAtMs: Long,
)
