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

    @Query("SELECT * FROM tags ORDER BY ordinal, id")
    fun allTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY ordinal, id")
    suspend fun tagsOnce(): List<TagEntity>

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

    @Query("DELETE FROM visual_media WHERE entryId = :id") suspend fun deleteVisuals(id: String)
    @Query("DELETE FROM audio WHERE entryId = :id") suspend fun deleteAudio(id: String)
    @Query("DELETE FROM feelings WHERE entryId = :id") suspend fun deleteFeeling(id: String)
    @Query("DELETE FROM entry_fts WHERE entryId = :id") suspend fun deleteFts(id: String)
    @Query("DELETE FROM entry_tags WHERE entryId = :id") suspend fun deleteEntryTags(id: String)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteEntry(id: String)
    @Query("DELETE FROM places WHERE id = :id AND id NOT IN (SELECT placeId FROM entries WHERE placeId IS NOT NULL)") suspend fun deleteUnusedPlace(id: String)
    @Query("SELECT relativePath FROM visual_media UNION SELECT relativePath FROM audio")
    suspend fun referencedFiles(): List<String>

    @Query("SELECT motionVideoPath FROM visual_media WHERE motionVideoPath IS NOT NULL")
    suspend fun referencedMotionVideos(): List<String>
}

@Database(
    entities = [EntryEntity::class, VisualMediaEntity::class, AudioEntity::class, FeelingEntity::class, PlaceEntity::class, SecurityStateEntity::class, EntryFtsEntity::class, TagEntity::class, EntryTagEntity::class],
    version = 3,
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
                // 预置标签在迁移里落库；已存在的 id 保留，避免覆盖用户机器上的旧值
                insertPresetTags(db)
            }
        }

        /** 预置标签落库：迁移和「全新安装建库」两条路径都必须走，否则新装用户拿不到标签。 */
        private fun insertPresetTags(db: SupportSQLiteDatabase) {
            TagCatalog.seed.forEach { tag ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `tags` (`id`, `name`, `ordinal`, `archived`) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(tag.id, tag.name, tag.ordinal, if (tag.archived) 1 else 0),
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(SEED_TAGS_ON_CREATE)
                .build().also { instance = it }
        }
    }
}
