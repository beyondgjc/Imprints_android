package com.beyondguo.imprints.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 未来信（时光邮箱）的附加元数据。
 *
 * 信件正文、媒体、音频与创建/修改/删除时间复用 `entries` / `visual_media` / `audio` 三张表，
 * 本表只保存「信」这个语义：标题、送达计划与阅读状态。
 * **凡是有本表关联行的 entries 行都是信件**，普通印迹的所有读取入口都必须排除它们
 * （见 `ContentKind` 与 `ImprintDao` 的排除条件），不能只在 UI 里过滤。
 *
 * 到期判定是三个不同维度：
 * - 「到期」= `deliveredAtMs != null` 或 `now >= deliverAtMs`（首次确认到期时把 `deliveredAtMs` 落库，时间回拨不回退）
 * - 「已读」= `deliveryReadAtMs != null`（到期后主动打开正文并成功显示才算）
 * - 「提前查看」= `firstOpenedAtMs != null`（记录首次打开，但不算收到未来送达）
 */
@Entity(
    tableName = "future_letters",
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deliverAtMs")],
)
data class FutureLetterEntity(
    @PrimaryKey val entryId: String,
    /** 信件标题；空串表示使用默认展示「给未来的自己」 */
    val title: String,
    /** 送达瞬间（epoch ms）。封存时按 deliverLocalMinute + deliverZoneId 解析后固定，不随时区漂移 */
    val deliverAtMs: Long,
    /** 封存时选定的本地时分，格式同 eventLocalMinute（yyyy-MM-dd'T'HH:mm） */
    val deliverLocalMinute: String,
    /** 封存时的时区 id */
    val deliverZoneId: String,
    /** 首次确认到期的时间；非空即表示已到期（不因时间回拨回退） */
    val deliveredAtMs: Long? = null,
    /** 首次打开信封（含提前查看）的时间 */
    val firstOpenedAtMs: Long? = null,
    /** 到期后成功显示正文的时间；非空 = 到期已读，取消首页自动置顶 */
    val deliveryReadAtMs: Long? = null,
    /** 到时通知开关；关闭不影响到期置顶 */
    val notifyEnabled: Boolean = true,
    /** 调度版本：封存/改期/重新封存都递增，旧版本任务据此判定作废 */
    val scheduleRevision: Int = 1,
)

/**
 * 通知投递的本机运行记录。**不跨设备恢复**（备份不导出）。
 * `(entryId, scheduleRevision)` 唯一：同一版本的任务重复执行时用稳定 notificationId 覆盖。
 */
@Entity(
    tableName = "letter_notification_events",
    primaryKeys = ["entryId", "scheduleRevision"],
    foreignKeys = [ForeignKey(entity = FutureLetterEntity::class, parentColumns = ["entryId"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")],
)
data class LetterNotificationEventEntity(
    val entryId: String,
    val scheduleRevision: Int,
    val notificationId: Int,
    val attemptState: String,
    val lastAttemptAtMs: Long? = null,
    val postedAtMs: Long? = null,
    val suppressedReason: String? = null,
)

/**
 * 邮箱列表/首页送达区的投影：**只含封面信息与状态**。
 * 列表加载不得顺带解码正文媒体；正文在用户主动拆信后才按需读取。
 */
data class LetterListRow(
    val entryId: String,
    val title: String,
    val deliverAtMs: Long,
    val deliverLocalMinute: String,
    val deliverZoneId: String,
    val deliveredAtMs: Long?,
    val firstOpenedAtMs: Long?,
    val deliveryReadAtMs: Long?,
    val notifyEnabled: Boolean,
    val scheduleRevision: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long?,
)
