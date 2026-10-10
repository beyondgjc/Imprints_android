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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 → v3 真实迁移测试（设备上跑，内存库不覆盖迁移路径）。
 *
 * 做法：用 `app/schemas/.../2.json` 里 Room 自己生成的原始 DDL 建一个 v2 库并写入一条旧印迹，
 * 再让 Room 以当前代码（version = 3 + MIGRATION_2_3）打开它。
 * 这样迁移 DDL 与 Room 期望的结构只要有一点不一致，Room 就会在这里直接抛错。
 */
@RunWith(AndroidJUnit4::class)
class ImprintMigration2To3Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "imprint-migration-test.db"
    private val legacyEntryId = "legacy-entry-v2"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        createVersion2Database()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 只搬 schema JSON 里记录的建表语句，不做任何手工改写，保证 v2 库与历史版本一致。 */
    private fun createVersion2Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V2_SCHEMA.forEach { db.execSQL(it) }
                        db.execSQL(
                            "INSERT INTO `entries` (`id`,`body`,`eventLocalMinute`,`eventZoneId`,`createdAtMs`,`updatedAtMs`) " +
                                "VALUES (?,?,?,?,?,?)",
                            arrayOf<Any>(legacyEntryId, "迁移前写下的旧印迹", "2026-01-02T08:30", "Asia/Shanghai", 1L, 1L),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        // 触发 onCreate（写 v2 结构）后立刻关闭，交给 Room 去升级
        @Suppress("UNUSED_EXPRESSION")
        helper.writableDatabase
        helper.close()
    }

    @Test
    fun migrationAddsTagsAndSeedsPresets() {
        val db = Room.databaseBuilder(context, ImprintDatabase::class.java, dbName)
            .addMigrations(ImprintDatabase.MIGRATION_1_2, ImprintDatabase.MIGRATION_2_3)
            .build()
        try {
            db.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
            // 迁移里种的预置标签
            db.openHelper.writableDatabase
                .query("SELECT id, name, ordinal, archived FROM tags ORDER BY ordinal")
                .use { cursor ->
                    assertEquals(TagCatalog.PRESETS.size, cursor.count)
                    cursor.moveToFirst()
                    TagCatalog.PRESETS.forEach { preset ->
                        assertEquals(preset.id, cursor.getString(0))
                        assertEquals(preset.name, cursor.getString(1))
                        assertEquals(preset.ordinal, cursor.getInt(2))
                        assertEquals(0, cursor.getInt(3))
                        cursor.moveToNext()
                    }
                }
            // 新关联表存在、可写、外键生效
            db.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='entry_tags'").use { cursor ->
                    assertEquals(1, cursor.count)
                }
            runBlocking {
                val id = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
                    .save(EntryDraft(body = "迁移后新建", eventLocalMinute = "2026-01-03T09:00", tagIds = listOf("personal")))
                assertEquals(listOf("personal"), db.dao().tagsOf(id))
                // 旧数据仍在，且未挂标签
                val legacy = db.dao().entry(legacyEntryId)
                assertEquals("迁移前写下的旧印迹", legacy?.body)
                assertTrue(db.dao().tagsOf(legacyEntryId).isEmpty())
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        /**
         * `app/schemas/com.beyondguo.imprints.data.ImprintDatabase/2.json` 中 createSql / indices 的逐字副本
         * （${TABLE_NAME} 已替换为真实表名）。schema 目录不会打进 APK assets，所以在这里内联；
         * 这些语句对应已发布的历史版本，不再变化。
         */
        val V2_SCHEMA = listOf(
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
    }
}
