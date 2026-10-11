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
    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    fun activeEntries(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NOT NULL ORDER BY deletedAtMs DESC")
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

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND id IN (SELECT entryId FROM entry_fts WHERE body MATCH :query) ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    suspend fun searchBody(query: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE deletedAtMs IS NULL AND instr(body, :text) > 0 ORDER BY eventLocalMinute DESC, createdAtMs DESC, id DESC")
    suspend fun searchBodySubstring(text: String): List<EntryEntity>

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
    entities = [EntryEntity::class, VisualMediaEntity::class, AudioEntity::class, FeelingEntity::class, PlaceEntity::class, SecurityStateEntity::class, EntryFtsEntity::class, TagEntity::class, EntryTagEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class ImprintDatabase : RoomDatabase() {
    abstract fun dao(): ImprintDao

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
         * 已有的 5 个预置标签按 id 回填为 preset = 1、createdAtMs = 0，展示顺序不变。
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SEED_TAGS_ON_CREATE)
                .build().also { instance = it }
        }
    }
}
