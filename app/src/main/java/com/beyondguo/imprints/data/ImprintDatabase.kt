package com.beyondguo.imprints.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries", indices = [Index(value = ["deletedAtMs", "eventLocalMinute", "createdAtMs"]), Index("placeId")])
data class EntryEntity(
    @PrimaryKey val id: String,
    val body: String,
    val eventLocalMinute: String,
    val eventZoneId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
    val placeId: String? = null,
)

@Entity(
    tableName = "visual_media",
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["entryId", "ordinal"], unique = true)],
)
data class VisualMediaEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val kind: String,
    val ordinal: Int,
    val relativePath: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    val createdAtMs: Long,
    /** 动态照片附属短视频（media 根目录 UUID 文件）；普通图片为 null */
    val motionVideoPath: String? = null,
    val motionVideoSha256: String? = null,
    val motionVideoByteLength: Long? = null,
)

@Entity(
    tableName = "audio",
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class AudioEntity(
    @PrimaryKey val entryId: String,
    val id: String,
    val relativePath: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    val durationMs: Long? = null,
)

@Entity(
    tableName = "feelings",
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
)
data class FeelingEntity(
    @PrimaryKey val entryId: String,
    val encoding: String,
    val payload: String,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ordinal: Int,
    val archived: Boolean = false,
    /** true = 内置预置标签（不可删除）；false = 用户自定义标签。 */
    val preset: Boolean = false,
    /** 自定义标签的创建时间，用于展示排序；预置标签为 0，靠 [ordinal] 排序。 */
    val createdAtMs: Long = 0,
)

/**
 * 印迹 ↔ 标签的关联表：一条印迹可挂 0~N 个标签。
 * 外键 CASCADE，删除印迹或标签时关联行自动清掉；复合主键自带 (entryId, tagId) 唯一索引。
 */
@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entryId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class EntryTagEntity(
    val entryId: String,
    val tagId: String,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val coordinateSystem: String?,
    val provider: String?,
    val providerPoiId: String?,
)

/** 「常用标签」的查询投影：标签 id 与它被多少条印迹使用。 */
data class TagUsageCount(val tagId: String, val count: Int)

@Entity(tableName = "security_state")
data class SecurityStateEntity(
    @PrimaryKey val id: Int = 1,
    val feelingMode: String = "OFF",
    val feelingHeaderJson: String? = null,
    val feelingPinEnvelope: String? = null,
    val feelingBiometricEnvelope: String? = null,
    val appLockHeaderJson: String? = null,
    val appLockVerifierB64: String? = null,
    val appLockBiometricEnabled: Boolean = false,
    val appLockFailedAttempts: Int = 0,
    val appLockBlockedUntilMs: Long = 0,
    val feelingFailedAttempts: Int = 0,
    val feelingBlockedUntilMs: Long = 0,
)

@Fts4(notIndexed = ["entryId"])
@Entity(tableName = "entry_fts")
data class EntryFtsEntity(
    val entryId: String,
    val body: String,
)

@Dao
interface ImprintDao {
    // 注意：以下所有面向「普通印迹」的查询都带 `NOT IN (SELECT entryId FROM future_letters)`。
    // 未来信复用 entries 行存正文，但**不是**普通印迹——不能只在 UI 里过滤。
    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id NOT IN (SELECT entryId FROM future_letters) ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    fun activeEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id NOT IN (SELECT entryId FROM future_letters) ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    suspend fun activeEntriesOnce(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NOT NULL AND id NOT IN (SELECT entryId FROM future_letters) ORDER BY deletedAtMs DESC")
    fun recycledEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun entry(id: String): EntryEntity?

    @Query("SELECT * FROM visual_media WHERE entryId = :id ORDER BY ordinal")
    suspend fun visuals(id: String): List<VisualMediaEntity>

    @Query("SELECT * FROM audio WHERE entryId = :id LIMIT 1")
    suspend fun audio(id: String): AudioEntity?

    @Query("SELECT * FROM feelings WHERE entryId = :id LIMIT 1")
    suspend fun feeling(id: String): FeelingEntity?

    @Query("SELECT * FROM places WHERE id = :id LIMIT 1")
    suspend fun place(id: String): PlaceEntity?

    @Query("SELECT * FROM security_state WHERE id = 1 LIMIT 1")
    suspend fun security(): SecurityStateEntity?

    @Query("SELECT * FROM feelings")
    suspend fun allFeelings(): List<FeelingEntity>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NOT NULL AND deletedAtMs <= :cutoff")
    suspend fun expired(cutoff: Long): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id NOT IN (SELECT entryId FROM future_letters) AND id IN (SELECT entryId FROM entry_fts WHERE body MATCH :query) ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    suspend fun searchBody(query: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id NOT IN (SELECT entryId FROM future_letters) AND instr(body, :text) > 0 ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    suspend fun searchBodySubstring(text: String): List<EntryEntity>

    /** 回顾候选（RV-02/RV-05）：只取普通、未删除、事件日期（含当日）落在范围内的印迹。 */
    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id NOT IN (SELECT entryId FROM future_letters) AND substr(eventLocalMinute, 1, 10) BETWEEN :start AND :end ORDER BY eventLocalMinute ASC, createdAtMs ASC, id ASC")
    suspend fun candidateEntries(start: String, end: String): List<EntryEntity>

    @Query("SELECT * FROM entries")
    suspend fun allEntries(): List<EntryEntity>

    @Query("SELECT * FROM visual_media")
    suspend fun allVisuals(): List<VisualMediaEntity>

    @Query("SELECT * FROM audio")
    suspend fun allAudio(): List<AudioEntity>

    @Query("SELECT * FROM places")
    suspend fun allPlaces(): List<PlaceEntity>

    @Query("SELECT * FROM tags ORDER BY preset DESC, ordinal, createdAtMs, id")
    fun allTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY preset DESC, ordinal, createdAtMs, id")
    suspend fun tagsOnce(): List<TagEntity>

    /** 时间线筛选栏与编辑页展示的标签：不含已停用。 */
    @Query("SELECT * FROM tags WHERE archived = 0 ORDER BY preset DESC, ordinal, createdAtMs, id")
    fun activeTags(): Flow<List<TagEntity>>

    @Query("SELECT COUNT(*) FROM tags WHERE archived = 0")
    suspend fun activeTagCount(): Int

    /** 删除确认用：这个标签挂了多少条印迹。 */
    @Query("SELECT COUNT(*) FROM entry_tags WHERE tagId = :id")
    suspend fun entryCountForTag(id: String): Int

    /** 「常用标签」用：一次取回所有标签的使用次数（不 N+1 查询）。 */
    @Query("SELECT tagId, COUNT(*) AS count FROM entry_tags GROUP BY tagId")
    suspend fun tagUsageCounts(): List<TagUsageCount>

    /** 时间线用：一次取回全部关联，交给内存按选中标签筛选。 */
    @Query("SELECT * FROM entry_tags")
    fun allEntryTags(): Flow<List<EntryTagEntity>>

    @Query("SELECT tagId FROM entry_tags WHERE entryId = :id")
    suspend fun tagsOf(id: String): List<String>

    @Query("SELECT * FROM entry_tags")
    suspend fun allEntryTagsOnce(): List<EntryTagEntity>

    @Upsert suspend fun upsertEntry(value: EntryEntity)
    @Upsert suspend fun upsertAudio(value: AudioEntity)
    @Upsert suspend fun upsertFeeling(value: FeelingEntity)
    @Upsert suspend fun upsertPlace(value: PlaceEntity)
    @Upsert suspend fun upsertSecurity(value: SecurityStateEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertVisuals(values: List<VisualMediaEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFts(value: EntryFtsEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEntryTags(values: List<EntryTagEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTags(values: List<TagEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTag(value: TagEntity)

    @Query("DELETE FROM visual_media WHERE entryId = :id") suspend fun deleteVisuals(id: String)
    @Query("DELETE FROM audio WHERE entryId = :id") suspend fun deleteAudio(id: String)
    @Query("DELETE FROM feelings WHERE entryId = :id") suspend fun deleteFeeling(id: String)
    @Query("DELETE FROM entry_fts WHERE entryId = :id") suspend fun deleteFts(id: String)
    @Query("DELETE FROM entry_tags WHERE entryId = :id") suspend fun deleteEntryTags(id: String)
    @Query("DELETE FROM entry_tags WHERE tagId = :tagId") suspend fun deleteEntryTagsForTag(tagId: String)
    @Query("UPDATE tags SET name = :name WHERE id = :id") suspend fun renameTag(id: String, name: String)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTag(id: String)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteEntry(id: String)
    @Query("DELETE FROM places WHERE id = :id AND id NOT IN (SELECT placeId FROM entries WHERE placeId IS NOT NULL)") suspend fun deleteUnusedPlace(id: String)
    @Query("SELECT relativePath FROM visual_media UNION SELECT relativePath FROM audio")
    suspend fun referencedFiles(): List<String>

    @Query("SELECT motionVideoPath FROM visual_media WHERE motionVideoPath IS NOT NULL")
    suspend fun referencedMotionVideos(): List<String>
}

@Database(
    entities = [
        EntryEntity::class, VisualMediaEntity::class, AudioEntity::class, FeelingEntity::class,
        PlaceEntity::class, SecurityStateEntity::class, EntryFtsEntity::class, TagEntity::class, EntryTagEntity::class,
        FutureLetterEntity::class, LetterNotificationEventEntity::class,
        ReviewEntity::class, ReviewSourceEntity::class, ReviewSourceMediaEntity::class,
        ContentDraftEntity::class, MediaReferenceEntity::class, ReviewInvitationStateEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class ImprintDatabase : RoomDatabase() {
    abstract fun dao(): ImprintDao
    abstract fun mailReviewDao(): MailReviewDao

    companion object {
        @Volatile private var instance: ImprintDatabase? = null

        /** v1→v2：动态照片附属视频字段 */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visual_media ADD COLUMN motionVideoPath TEXT")
                db.execSQL("ALTER TABLE visual_media ADD COLUMN motionVideoSha256 TEXT")
                db.execSQL("ALTER TABLE visual_media ADD COLUMN motionVideoByteLength INTEGER")
            }
        }

        /**
         * v2→v3：标签体系。DDL 必须与 Room 生成的 `app/schemas/…/3.json` 完全一致，
         * 否则校验不过会在打开数据库时直接崩溃。
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `entry_tags` (`entryId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`entryId`, `tagId`), " +
                        "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_tags_tagId` ON `entry_tags` (`tagId`)")
                // 此处只建 v3 的 4 列结构，preset/createdAtMs 由 MIGRATION_3_4 追加
                TagCatalog.PRESETS.forEach { preset ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `tags` (`id`, `name`, `ordinal`, `archived`) VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(preset.id, preset.name, preset.ordinal, if (preset.archived) 1 else 0),
                    )
                }
            }
        }

        /**
         * v3→v4：标签支持用户自定义。新增 `preset`（预置/自定义）与 `createdAtMs`（自定义标签排序）。
         * 已有的预置标签按 id 回填为 preset = 1、createdAtMs = 0，展示顺序不变
         * （v5 起预置标签收敛为三个，本步只是过渡，启停与改名由 [MIGRATION_4_5] 收口）。
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tags` ADD COLUMN `preset` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tags` ADD COLUMN `createdAtMs` INTEGER NOT NULL DEFAULT 0")
                TagCatalog.PRESETS.forEach { preset ->
                    db.execSQL(
                        "UPDATE `tags` SET `preset` = 1, `createdAtMs` = 0, `ordinal` = ? WHERE `id` = ?",
                        arrayOf<Any>(preset.ordinal, preset.id),
                    )
                }
            }
        }

        /**
         * v4→v5：预置标签收敛为「家事 / 国事 / 天下事」三个。
         *
         * 旧库里的 personal / national / internet 三行只更新展示名与序号，行本身保留——
         * 它们可能已被印迹引用，换 id 会丢掉挂载关系；「工作 / 旅行」两行改为停用
         * （archived = 1），既不从选择与筛选里出现，也不破坏历史挂载与旧备份。
         *
         * 用 INSERT OR IGNORE + UPDATE 而非只 UPDATE：极旧的库若缺行也能补齐（正常路径下它们已存在）。
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                TagCatalog.seed.forEach { tag ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `tags` (`id`, `name`, `ordinal`, `archived`, `preset`, `createdAtMs`) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(tag.id, tag.name, tag.ordinal, if (tag.archived) 1 else 0, if (tag.preset) 1 else 0, tag.createdAtMs),
                    )
                    db.execSQL(
                        "UPDATE `tags` SET `name` = ?, `ordinal` = ?, `archived` = 0 WHERE `id` = ?",
                        arrayOf<Any>(tag.name, tag.ordinal, tag.id),
                    )
                }
                val retiredIds = TagCatalog.presetIds - TagCatalog.PRESETS.map { it.id }.toSet()
                retiredIds.sorted().forEachIndexed { i, id ->
                    db.execSQL(
                        "UPDATE `tags` SET `archived` = 1, `ordinal` = ? WHERE `id` = ?",
                        arrayOf<Any>(TagCatalog.RETIRED_ORDINAL_BASE + i, id),
                    )
                }
            }
        }

        /**
         * v5→v6：时光邮箱与回顾。
         *
         * 新增信件元数据、通知投递记录、总结/来源快照/快照媒体、草稿、媒体所有权与邀请偏好。
         * 普通印迹与媒体表原样保留；信件复用 entries 行存正文，靠 future_letters 关联行区分。
         * DDL 必须与 Room 生成的 `app/schemas/…/6.json` 的 createSql 结构一致。
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `future_letters` (`entryId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`deliverAtMs` INTEGER NOT NULL, `deliverLocalMinute` TEXT NOT NULL, `deliverZoneId` TEXT NOT NULL, " +
                        "`deliveredAtMs` INTEGER, `firstOpenedAtMs` INTEGER, `deliveryReadAtMs` INTEGER, " +
                        "`notifyEnabled` INTEGER NOT NULL, `scheduleRevision` INTEGER NOT NULL, PRIMARY KEY(`entryId`), " +
                        "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_future_letters_deliverAtMs` ON `future_letters` (`deliverAtMs`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `letter_notification_events` (`entryId` TEXT NOT NULL, " +
                        "`scheduleRevision` INTEGER NOT NULL, `notificationId` INTEGER NOT NULL, `attemptState` TEXT NOT NULL, " +
                        "`lastAttemptAtMs` INTEGER, `postedAtMs` INTEGER, `suppressedReason` TEXT, " +
                        "PRIMARY KEY(`entryId`, `scheduleRevision`), " +
                        "FOREIGN KEY(`entryId`) REFERENCES `future_letters`(`entryId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_letter_notification_events_entryId` ON `letter_notification_events` (`entryId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reviews` (`id` TEXT NOT NULL, `periodKind` TEXT NOT NULL, " +
                        "`startLocalDate` TEXT NOT NULL, `endLocalDateInclusive` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`userBody` TEXT NOT NULL, `closingText` TEXT NOT NULL, `coverSourceKey` TEXT, " +
                        "`selectedTagIds` TEXT NOT NULL, `tagNameSnapshot` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `deletedAtMs` INTEGER, " +
                        "`snapshotRevision` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reviews_deletedAtMs` ON `reviews` (`deletedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reviews_updatedAtMs` ON `reviews` (`updatedAtMs`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_sources` (`reviewId` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, " +
                        "`sourceId` TEXT NOT NULL, `sourceUpdatedAtMs` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                        "`eventDate` TEXT NOT NULL, `included` INTEGER NOT NULL, `featured` INTEGER NOT NULL, " +
                        "`featuredOrder` INTEGER NOT NULL, `titleSnapshot` TEXT NOT NULL, `bodySnapshot` TEXT NOT NULL, " +
                        "`tagSnapshot` TEXT NOT NULL, `visibility` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`reviewId`, `sourceKind`, `sourceId`), " +
                        "FOREIGN KEY(`reviewId`) REFERENCES `reviews`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_sources_reviewId` ON `review_sources` (`reviewId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_sources_sourceId` ON `review_sources` (`sourceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_source_media` (`id` TEXT NOT NULL, `reviewId` TEXT NOT NULL, " +
                        "`sourceKind` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `role` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, `byteLength` INTEGER NOT NULL, " +
                        "`sha256` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`reviewId`, `sourceKind`, `sourceId`) REFERENCES `review_sources`(`reviewId`, `sourceKind`, `sourceId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_source_media_reviewId` ON `review_source_media` (`reviewId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_source_media_reviewId_sourceKind_sourceId` ON `review_source_media` (`reviewId`, `sourceKind`, `sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_source_media_relativePath` ON `review_source_media` (`relativePath`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `content_drafts` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`targetId` TEXT, `payload` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_drafts_type` ON `content_drafts` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_drafts_updatedAtMs` ON `content_drafts` (`updatedAtMs`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_references` (`ownerKind` TEXT NOT NULL, `ownerId` TEXT NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, PRIMARY KEY(`ownerKind`, `ownerId`, `relativePath`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_references_relativePath` ON `media_references` (`relativePath`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_invitation_state` (`periodKind` TEXT NOT NULL, " +
                        "`startLocalDate` TEXT NOT NULL, `endLocalDateInclusive` TEXT NOT NULL, `closedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`periodKind`, `startLocalDate`, `endLocalDateInclusive`))",
                )
            }
        }

        /** 预置标签落库：迁移和「全新安装建库」两条路径都必须走，否则新装用户拿不到标签。 */
        private fun insertPresetTags(db: SupportSQLiteDatabase) {
            TagCatalog.seed.forEach { tag ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `tags` (`id`, `name`, `ordinal`, `archived`, `preset`, `createdAtMs`) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(tag.id, tag.name, tag.ordinal, if (tag.archived) 1 else 0, if (tag.preset) 1 else 0, tag.createdAtMs),
                )
            }
        }

        /**
         * 全新安装时 Room 只按实体建表，不跑任何 Migration，
         * 所以预置标签必须在这里补种——迁移路径由 [MIGRATION_2_3] 负责。
         */
        internal val SEED_TAGS_ON_CREATE = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = insertPresetTags(db)
        }

        fun get(context: Context): ImprintDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ImprintDatabase::class.java, "imprints.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(SEED_TAGS_ON_CREATE)
                .build().also { instance = it }
        }
    }
}
