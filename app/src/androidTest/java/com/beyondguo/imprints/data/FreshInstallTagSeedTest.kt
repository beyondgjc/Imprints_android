package com.beyondguo.imprints.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 全新安装建库路径：Room 直接按实体建表、**不跑任何 Migration**。
 * 预置标签必须由 [ImprintDatabase.SEED_TAGS_ON_CREATE] 补种，
 * 否则新装用户编辑页的标签区会整段消失（历史回归点）。
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallTagSeedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "imprint-fresh-install-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun openDatabase() = Room.databaseBuilder(context, ImprintDatabase::class.java, dbName)
        .addMigrations(ImprintDatabase.MIGRATION_1_2, ImprintDatabase.MIGRATION_2_3, ImprintDatabase.MIGRATION_3_4)
        .addCallback(ImprintDatabase.SEED_TAGS_ON_CREATE)
        .build()

    @Test
    fun freshDatabaseSeedsPresetTags() {
        val db = openDatabase()
        try {
            runBlocking {
                val tags = db.dao().tagsOnce()
                assertEquals(TagCatalog.PRESETS.size, tags.size)
                assertEquals(TagCatalog.PRESETS.map { it.id }, tags.map { it.id })
                assertEquals(TagCatalog.PRESETS.map { it.name }, tags.map { it.name })
                assertTrue(tags.all { it.preset })
                assertTrue(tags.all { it.createdAtMs == 0L })
                assertEquals(TagCatalog.PRESETS.size, db.dao().activeTagCount())

                // 开箱即可挂载预置标签，也可以直接新建自定义标签
                val repository = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
                val custom = repository.createTag("读书")
                val id = repository.save(
                    EntryDraft(
                        body = "新装第一条",
                        eventLocalMinute = "2026-10-11T10:00",
                        tagIds = listOf(custom, "未知标签"),
                    ),
                )
                // 预置在前、自定义在后；未知 id 被丢弃
                assertEquals(listOf(custom), repository.detail(id)!!.tagIds)
                assertEquals(TagCatalog.PRESETS.size + 1, repository.selectableTags.first().size)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun seedingIsIdempotentAcrossReopen() {
        openDatabase().close()
        // 第二次打开不会重建库，也不该产生重复标签
        val db = openDatabase()
        try {
            runBlocking {
                assertEquals(TagCatalog.PRESETS.size, db.dao().tagsOnce().size)
                assertNotNull(db.dao().tagsOnce().firstOrNull { it.preset })
            }
        } finally {
            db.close()
        }
    }
}
