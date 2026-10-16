package com.beyondguo.imprints.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.flow.first
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
 * 再让 Room 以当前代码（version = 6 + 迁移链）打开它。
 * 迁移 DDL 与 Room 期望的结构只要有一点不一致，Room 就会在这里直接抛错。
 *
 * 重点覆盖：
 * - v4→v5：预置标签由 5 个收敛为「家事 / 国事 / 天下事」3 个，旧行原地保留，挂载关系不丢；
 * - v5→v6：新增时光邮箱（信件）与回顾（总结/快照/草稿）表，普通印迹与媒体原样保留，
 *   信件与总结可以正常读写，且信件不出现在普通时间线/搜索里。
 */
@RunWith(AndroidJUnit4::class)
class ImprintMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "imprint-migration-test.db"
    private val legacyEntryId = "legacy-entry-v2"

    /** v4 时代的 5 个预置标签（当时的名称与序号），用于验证 v4→v5 收敛。 */
    private val v4Presets = listOf(
        TagEntity("personal", "个人经历", 0, false, preset = true, createdAtMs = 0),
        TagEntity("national", "国家大事", 1, false, preset = true, createdAtMs = 0),
        TagEntity("internet", "互联网热点", 2, false, preset = true, createdAtMs = 0),
        TagEntity("work", "工作", 3, false, preset = true, createdAtMs = 0),
        TagEntity("travel", "旅行", 4, false, preset = true, createdAtMs = 0),
    )

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun openWithRoom() = Room.databaseBuilder(context, ImprintDatabase::class.java, dbName)
        .addMigrations(
            ImprintDatabase.MIGRATION_1_2,
            ImprintDatabase.MIGRATION_2_3,
            ImprintDatabase.MIGRATION_3_4,
            ImprintDatabase.MIGRATION_4_5,
            ImprintDatabase.MIGRATION_5_6,
        )
        .build()

    /** 按给定 DDL 建一个指定 user_version 的旧库，可选地在 tags 里预置几行（v3 的 4 列写法）。 */
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

    /** 在已建好的旧库上按指定 user_version 执行一段 SQL（用完即关）。 */
    private fun withLegacyDb(version: Int, block: (SupportSQLiteDatabase) -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.use { block(it.writableDatabase) }
    }

    /**
     * 按当年的预置目录回填 preset / ordinal / createdAtMs。
     *
     * 对应 `MIGRATION_3_4` 在发布时的行为——那时目录里还是 5 个预置。
     * 现在代码里的 [TagCatalog.PRESETS] 已收敛为 3 个，v3 旧库若只靠当前链去迁移，
     * 「工作 / 旅行」不会被打上 preset 标记，与真实旧库不符。
     */
    private fun markV4Presets(db: SupportSQLiteDatabase) {
        v4Presets.forEach { preset ->
            db.execSQL(
                "UPDATE `tags` SET `preset` = 1, `createdAtMs` = 0, `ordinal` = ? WHERE `id` = ?",
                arrayOf<Any>(preset.ordinal, preset.id),
            )
        }
    }

    @Test
    fun migrationFromV3KeepsTagsAndPreservesOldData() {
        val custom = TagEntity("custom-1", "读书", ordinal = 0)
        // v3 时代落库的就是当时的 5 个预置，直接用它们的原名，别拿当前目录（已收敛为 3 个）去种
        createLegacyDatabase(version = 3, schema = V3_SCHEMA, seedTags = v4Presets + custom)
        // 该库当前已是 v4：tags 补上 v4 的两列并按当年目录回填（等价于当年的 MIGRATION_3_4）
        withLegacyDb(version = 4) { legacy ->
            legacy.execSQL("ALTER TABLE `tags` ADD COLUMN `preset` INTEGER NOT NULL DEFAULT 0")
            legacy.execSQL("ALTER TABLE `tags` ADD COLUMN `createdAtMs` INTEGER NOT NULL DEFAULT 0")
            markV4Presets(legacy)
            legacy.execSQL("PRAGMA user_version = 4")
        }
        val db = openWithRoom()
        try {
            assertEquals(6, userVersion(db))
            runBlocking {
                val tags = db.dao().tagsOnce()
                // v3 时代落库的 5 个预置行 + 1 个自定义标签，一个都不能丢（v5 只是停用 work/travel，不删行）
                assertEquals(6, tags.size)
                val presets = tags.filter { it.preset }
                assertEquals(5, presets.size)
                assertEquals(TagCatalog.presetIds, presets.map { it.id }.toSet())
                // 「工作 / 旅行」在 v5 被停用；v3 时代那个自定义标签仍然可用
                assertEquals(
                    TagCatalog.PRESETS.map { it.id } + "custom-1",
                    db.dao().activeTags().first().map { it.id },
                )
                assertTrue(presets.filter { it.id in TagCatalog.PRESETS.map { p -> p.id } }.all { it.createdAtMs == 0L })
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

    /**
     * v4→v5：预置标签收敛为「家事 / 国事 / 天下事」。
     *
     * 旧库里的 personal 行带着一条挂载，迁移后必须还在——这正是不能换 id 的原因。
     */
    @Test
    fun migrationFromV4RenamesPresetsArchivesRetiredAndKeepsLinks() {
        /**
         * v4 旧库按真实写法构造：先建 4 列的 tags，再用 ALTER 补 v4 的两列并回填——
         * 与 [ImprintDatabase.MIGRATION_3_4] 的做法一致。
         *
         * 不直接建成 6 列表：v4 与 v5 的表结构完全相同，直接建成终态可能被 Room 判定为
         * 「结构已符合」而不跑 [ImprintDatabase.MIGRATION_4_5]，这条用例就失去意义了。
         */
        createLegacyDatabase(version = 3, schema = V3_SCHEMA)
        withLegacyDb(version = 4) { legacy ->
            legacy.execSQL("ALTER TABLE `tags` ADD COLUMN `preset` INTEGER NOT NULL DEFAULT 0")
            legacy.execSQL("ALTER TABLE `tags` ADD COLUMN `createdAtMs` INTEGER NOT NULL DEFAULT 0")
            v4Presets.forEach { preset ->
                legacy.execSQL(
                    "INSERT INTO `tags` (`id`,`name`,`ordinal`,`archived`,`preset`,`createdAtMs`) VALUES (?,?,?,?,?,?)",
                    arrayOf<Any>(preset.id, preset.name, preset.ordinal, 0, 1, 0L),
                )
            }
            legacy.execSQL(
                "INSERT INTO `entry_tags` (`entryId`, `tagId`) VALUES (?, ?)",
                arrayOf<Any>(legacyEntryId, "personal"),
            )
            legacy.execSQL("PRAGMA user_version = 4")
        }

        val db = openWithRoom()
        try {
            assertEquals(6, userVersion(db))
            runBlocking {
                val tags = db.dao().tagsOnce()
                // 5 行原样保留：3 个启用（改名）+ 2 个停用
                assertEquals(5, tags.size)

                val active = db.dao().activeTags().first()
                assertEquals(TagCatalog.PRESETS.map { it.id }, active.map { it.id })
                assertEquals(listOf("家事", "国事", "天下事"), active.map { it.name })
                assertEquals(listOf(0, 1, 2), active.map { it.ordinal })
                assertTrue(active.all { it.preset && it.createdAtMs == 0L && !it.archived })

                val archived = tags.filter { it.archived }
                assertEquals(setOf("work", "travel"), archived.map { it.id }.toSet())
                // 停用项排在启用项之后，且保留原名
                assertTrue(archived.all { it.ordinal >= TagCatalog.RETIRED_ORDINAL_BASE })
                assertEquals("工作", tags.first { it.id == "work" }.name)
                assertEquals("旅行", tags.first { it.id == "travel" }.name)

                // 旧挂载仍在（旧印迹仍能查到「家事」）
                assertEquals(listOf("personal"), db.dao().tagsOf(legacyEntryId))
                // 停用项依旧算预置，因此不可删除
                assertTrue(TagCatalog.isPreset("work"))
                assertTrue(TagCatalog.isPreset("travel"))
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
            assertEquals(6, userVersion(db))
            runBlocking {
                val tags = db.dao().tagsOnce()
                // v2 没有标签表，迁移链一路补到 v5：只种当前的 3 个启用预置，不产生停用行
                assertEquals(TagCatalog.PRESETS.size, tags.size)
                assertEquals(TagCatalog.PRESETS.map { it.id }, tags.map { it.id })
                assertEquals(TagCatalog.PRESETS.map { it.name }, tags.map { it.name })
                assertTrue(tags.all { it.preset })
                assertTrue(tags.all { it.createdAtMs == 0L })
                assertTrue(tags.none { it.archived })
                // v2 老印迹没有标签，升级后仍可读
                assertEquals("迁移前写下的旧印迹", db.dao().entry(legacyEntryId)?.body)
                assertTrue(db.dao().tagsOf(legacyEntryId).isEmpty())
            }
        } finally {
            db.close()
        }
    }

    /**
     * v5→v6：新增时光邮箱（信件）与回顾（总结/快照/草稿）表。
     *
     * 旧库升级后：普通印迹与标签原样保留；新表完整可用；
     * 信件写入后**不出现在**普通时间线/搜索里（类型隔离在数据层，不是 UI 过滤）。
     */
    @Test
    fun migrationFromV5AddsMailAndReviewTables() {
        // v5 与 v4 表结构相同（v4→v5 只改行不改表），旧库按 v4 结构建成 user_version = 5
        createLegacyDatabase(version = 5, schema = V4_SCHEMA)
        val db = openWithRoom()
        try {
            assertEquals(6, userVersion(db))
            runBlocking {
                // 原有数据不动（createLegacyDatabase 对 version > 2 写入的是 v3 文案）
                assertEquals("v3 时代写下的印迹", db.dao().entry(legacyEntryId)?.body)

                val files = MediaFiles(context)
                val security = ImprintSecurity(db)
                val core = ImprintRepository(db, files, security)
                val letters = LetterRepository(db, files, core)
                val reviews = ReviewRepository(db, files, core)

                // 新表可用：信件可封存
                val letterId = letters.seal(
                    LetterDraft(
                        title = "未来",
                        body = "给未来的一封信",
                        deliverLocalMinute = "2099-01-01T09:00",
                        deliverZoneId = "Asia/Shanghai",
                    ),
                )
                // 类型隔离：信件不出现在普通时间线/搜索里
                assertTrue(db.dao().activeEntriesOnce().none { it.id == letterId })
                assertTrue(core.searchBody("给未来的一封信").none { it.id == letterId })
                assertEquals(ContentKind.LETTER, core.contentKind(letterId))
                assertEquals(ContentKind.ENTRY, core.contentKind(legacyEntryId))
                assertEquals("给未来的一封信", letters.letterDetail(letterId)?.entry?.body)
                assertEquals(1, db.mailReviewDao().sealedLettersList().size)

                // 总结可保存，且不作为普通印迹候选
                val reviewId = reviews.save(
                    ReviewDraft(
                        periodKind = "MONTH",
                        startLocalDate = "2026-01-01",
                        endLocalDateInclusive = "2026-01-31",
                        title = "一月回顾",
                        userBody = "总结正文",
                    ),
                )
                assertEquals("一月回顾", reviews.review(reviewId)?.title)
                assertEquals(ContentKind.REVIEW, core.contentKind(reviewId))
                assertTrue(db.dao().activeEntriesOnce().none { it.id == reviewId })
                // 旧印迹仍可被回顾候选读取（按事件日期归类）
                val january = com.beyondguo.imprints.review.DateRange(
                    java.time.LocalDate.of(2026, 1, 1),
                    java.time.LocalDate.of(2026, 1, 31),
                )
                assertEquals(listOf(legacyEntryId), reviews.candidates(january, emptySet()).map { it.entry.id })
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

        /** v4 的 tags 建表语句（比 v3 多 preset / createdAtMs 两列），取自 `…/4.json`。 */
        val V4_TAGS_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `preset` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `entry_tags` (`entryId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`entryId`, `tagId`), FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_entry_tags_tagId` ON `entry_tags` (`tagId`)",
        )

        /** v4 完整结构：v2 业务表 + v4 的 tags / entry_tags。 */
        val V4_SCHEMA = V2_ENTITIES_DDL + V4_TAGS_DDL

        val V2_SCHEMA = V2_ENTITIES_DDL
    }
}
