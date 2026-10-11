package com.beyondguo.imprints.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移实测（设备上跑，内存库不覆盖迁移路径）。
 *
 * 做法：用历史 `app/schemas/.../{2,3}.json` 里 Room 自己生成的原始 DDL 建成旧库并写入旧数据，
 * 再让 Room 以当前代码（version = 4 + 迁移链）打开它。
 * 迁移 DDL 与 Room 期望的结构只要有一点不一致，Room 就会在这里直接抛错。
 *
 * 重点覆盖 v3→v4：真机用户当前就在 v3（`9de1560 feat：固定标签`）。
 */
@RunWith(AndroidJUnit4::class)
class ImprintMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "imprint-migration-test.db"
    private val legacyEntryId = "legacy-entry-v2"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun openWithRoom() = Room.databaseBuilder(context, ImprintDatabase::class.java, dbName)
        .addMigrations(ImprintDatabase.MIGRATION_1_2, ImprintDatabase.MIGRATION_2_3, ImprintDatabase.MIGRATION_3_4)
        .build()

    /** 按给定 DDL 建一个指定 user_version 的旧库，可选地在 tags 里预置几行。 */
    private fun createLegacyDatabase(version: Int, schema: List<String>, seedTags: List<TagEntity> = emptyList()) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.forEach { db.execSQL(it) }
                        db.execSQL(
                            "INSERT INTO `entries` (`id`,`body`,`eventLocalMinute`,`eventZoneId`,`createdAtMs`,`updatedAtMs`) " +
                                "VALUES (?,?,?,?,?,?)",
                            arrayOf<Any>(
                                legacyEntryId,
                                if (version <= 2) "迁移前写下的旧印迹" else "v3 时代写下的印迹",
                                "2026-01-02T08:30", "Asia/Shanghai", 1L, 1L,
                            ),
                        )
                        seedTags.forEach { tag ->
                            db.execSQL(
                                "INSERT INTO `tags` (`id`,`name`,`ordinal`,`archived`) VALUES (?,?,?,?)",
                                arrayOf<Any>(tag.id, tag.name, tag.ordinal, if (tag.archived) 1 else 0),
                            )
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        @Suppress("UNUSED_EXPRESSION")
        helper.writableDatabase
        helper.close()
    }

    private fun userVersion(db: ImprintDatabase): Int =
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun migrationFromV3KeepsTagsAndPreservesOldData() {
        val custom = TagEntity("custom-1", "读书", ordinal = 0)
        createLegacyDatabase(version = 3, schema = V3_SCHEMA, seedTags = TagCatalog.seed.map { it.copy(preset = false) } + custom)
        val db = openWithRoom()
        try {
            assertEquals(4, userVersion(db))
            runBlocking {
                val tags = db.dao().tagsOnce()
                // 5 个预置 + 1 个 v3 时代的自定义标签，一个都不能丢
                assertEquals(6, tags.size)
                val presets = tags.filter { it.preset }
                assertEquals(TagCatalog.PRESETS.size, presets.size)
                assertEquals(TagCatalog.PRESETS.map { it.id }, presets.sortedBy { it.ordinal }.map { it.id })
                assertTrue(presets.all { it.createdAtMs == 0L })
                // v3 里没有 preset 概念，迁移后按 id 回填：目录内的是预置，其余是自定义
                val customAfter = tags.first { it.id == "custom-1" }
                assertFalse(customAfter.preset)
                assertEquals("读书", customAfter.name)
                // 旧印迹仍在
                assertEquals("v3 时代写下的印迹", db.dao().entry(legacyEntryId)?.body)
                // 迁移后可以正常新建 / 挂自定义标签
                val repository = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
                val newTag = repository.createTag("跑步")
                val entry = repository.save(
                    EntryDraft(body = "迁移后新建", eventLocalMinute = "2026-01-03T09:00", tagIds = listOf(newTag)),
                )
                assertEquals(listOf(newTag), repository.detail(entry)!!.tagIds)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationChainFromV2SeedsPresets() {
        createLegacyDatabase(version = 2, schema = V2_SCHEMA)
        val db = openWithRoom()
        try {
            assertEquals(4, userVersion(db))
            runBlocking {
                val tags = db.dao().tagsOnce()
                assertEquals(TagCatalog.PRESETS.size, tags.size)
                assertEquals(TagCatalog.PRESETS.map { it.id }, tags.map { it.id })
                assertTrue(tags.all { it.preset })
                assertTrue(tags.all { it.createdAtMs == 0L })
                // v2 老印迹没有标签，升级后仍可读
                assertEquals("迁移前写下的旧印迹", db.dao().entry(legacyEntryId)?.body)
                assertTrue(db.dao().tagsOf(legacyEntryId).isEmpty())
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        /** `…/3.json` 中 tags / entry_tags 的 createSql 逐字副本。 */
        val V3_TAGS_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `entry_tags` (`entryId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`entryId`, `tagId`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_entry_tags_tagId` ON `entry_tags` (`tagId`)",
        )

        /**
         * `…/2.json` 的建表语句逐字副本（${TABLE_NAME} 已替换为真实表名）。
         * schema 目录不会打进 APK assets，所以内联；这些语句对应已发布的历史版本，不再变化。
         */
        val V2_ENTITIES_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `entries` (`id` TEXT NOT NULL, `body` TEXT NOT NULL, `eventLocalMinute` TEXT NOT NULL, `eventZoneId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `deletedAtMs` INTEGER, `placeId` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_entries_deletedAtMs_eventLocalMinute_createdAtMs` ON `entries` (`deletedAtMs`, `eventLocalMinute`, `createdAtMs`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_placeId` ON `entries` (`placeId`)",
            "CREATE TABLE IF NOT EXISTS `visual_media` (`id` TEXT NOT NULL, `entryId` TEXT NOT NULL, `kind` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, `mime` TEXT NOT NULL, `byteLength` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `motionVideoPath` TEXT, `motionVideoSha256` TEXT, `motionVideoByteLength` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_visual_media_entryId_ordinal` ON `visual_media` (`entryId`, `ordinal`)",
            "CREATE TABLE IF NOT EXISTS `audio` (`entryId` TEXT NOT NULL, `id` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `mime` TEXT NOT NULL, `byteLength` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `durationMs` INTEGER, PRIMARY KEY(`entryId`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `feelings` (`entryId` TEXT NOT NULL, `encoding` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`entryId`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `coordinateSystem` TEXT, `provider` TEXT, `providerPoiId` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `security_state` (`id` INTEGER NOT NULL, `feelingMode` TEXT NOT NULL, `feelingHeaderJson` TEXT, `feelingPinEnvelope` TEXT, `feelingBiometricEnvelope` TEXT, `appLockHeaderJson` TEXT, `appLockVerifierB64` TEXT, `appLockBiometricEnabled` INTEGER NOT NULL, `appLockFailedAttempts` INTEGER NOT NULL, `appLockBlockedUntilMs` INTEGER NOT NULL, `feelingFailedAttempts` INTEGER NOT NULL, `feelingBlockedUntilMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `entry_fts` USING FTS4(`entryId` TEXT NOT NULL, `body` TEXT NOT NULL, notindexed=`entryId`)",
        )

        /** v2 的 7 张业务表 + v3 新增的两张标签表 = v3 完整结构。 */
        val V3_SCHEMA = V2_ENTITIES_DDL + V3_TAGS_DDL
        val V2_SCHEMA = V2_ENTITIES_DDL
    }
}
