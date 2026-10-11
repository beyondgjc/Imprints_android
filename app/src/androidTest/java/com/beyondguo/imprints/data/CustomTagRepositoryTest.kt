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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 标签的增删改与限流（设备上跑，真库）。 */
@RunWith(AndroidJUnit4::class)
class CustomTagRepositoryTest {
    private lateinit var db: ImprintDatabase
    private lateinit var repository: ImprintRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImprintDatabase::class.java)
            .addCallback(ImprintDatabase.SEED_TAGS_ON_CREATE)
            .build()
        repository = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(body: String, tags: List<String> = emptyList(), id: String? = null) = EntryDraft(
        id = id,
        body = body,
        eventLocalMinute = "2026-10-12T09:00",
        tagIds = tags,
    )

    private suspend fun presetId(index: Int) = db.dao().tagsOnce().first { it.preset && it.ordinal == index }.id

    @Test
    fun createTagAppendsCustomTagAfterPresets() = runBlocking {
        val id = repository.createTag("  读书  ")
        val tags = repository.selectableTags.first()
        assertEquals(TagCatalog.PRESETS.size + 1, tags.size)
        // 预置在前，自定义在后
        assertEquals(id, tags.last().id)
        assertEquals("读书", tags.last().name)
        assertFalse(tags.last().preset)
    }

    @Test
    fun createTagReusesExistingNameInsteadOfDuplicating() = runBlocking {
        val first = repository.createTag("读书")
        // 首尾空白视为同一个标签
        val again = repository.createTag("  读书 ")
        assertEquals(first, again)
        assertEquals(TagCatalog.PRESETS.size + 1, repository.selectableTags.first().size)
    }

    @Test
    fun createTagRejectsDuplicateOfPresetName() = runBlocking {
        // 与预置标签同名同样走「复用」，返回预置标签的 id，不新建
        val presetName = TagCatalog.PRESETS.first().name
        val presetId = presetId(0)
        val returned = repository.createTag(presetName)
        assertEquals(presetId, returned)
        assertEquals(TagCatalog.PRESETS.size, repository.selectableTags.first().size)
    }

    @Test
    fun createTagRejectsEmptyAndTooLongNames() = runBlocking {
        assertTrue(runCatching { repository.createTag("   ") }.exceptionOrNull() is IllegalArgumentException)
        val tooLong = runCatching { repository.createTag("一".repeat(TagRules.MAX_NAME_LENGTH + 1)) }
        assertTrue(tooLong.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun createTagStopsAtLimit() = runBlocking {
        val existing = db.dao().activeTagCount()
        repeat(TagRules.MAX_TAG_COUNT - existing) { index ->
            repository.createTag("自定义$index")
        }
        assertEquals(TagRules.MAX_TAG_COUNT, repository.activeTagCount())
        val result = runCatching { repository.createTag("再来一个") }
        val error = result.exceptionOrNull()
        assertTrue("应抛出 IllegalStateException，实际=$result", error is IllegalStateException)
        assertTrue(error!!.message!!.contains(TagRules.MAX_TAG_COUNT.toString()))
    }

    @Test
    fun renameTagUpdatesNameAndKeepsAttachment() = runBlocking {
        val id = repository.createTag("读书")
        val entry = repository.save(draft("读了本书", listOf(id)))
        repository.renameTag(id, "阅读")
        assertEquals("阅读", repository.selectableTags.first().first { it.id == id }.name)
        assertEquals(listOf(id), repository.detail(entry)!!.tagIds)
    }

    @Test
    fun renameTagRejectsCollision() = runBlocking {
        val id = repository.createTag("读书")
        repository.createTag("运动")
        val result = runCatching { repository.renameTag(id, "运动") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        // 原名保持不变
        assertEquals("读书", repository.selectableTags.first().first { it.id == id }.name)
    }

    @Test
    fun deleteTagDetachesFromEntriesButKeepsThem() = runBlocking {
        val id = repository.createTag("读书")
        val entry = repository.save(draft("读了本书", listOf(id)))
        assertEquals(1, repository.entryCountForTag(id))
        repository.deleteTag(id)
        assertTrue(repository.selectableTags.first().none { it.id == id })
        assertTrue(repository.entryTags.first().none { it.tagId == id })
        // 印迹本体保留，只是没有标签了
        assertNotNull(repository.detail(entry))
        assertTrue(repository.detail(entry)!!.tagIds.isEmpty())
    }

    @Test
    fun deleteTagRejectsPreset() = runBlocking {
        val preset = presetId(0)
        val result = runCatching { repository.deleteTag(preset) }
        val error = result.exceptionOrNull()
        assertTrue("应抛出 IllegalStateException，实际=$result", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("预置"))
        assertNotNull(repository.selectableTags.first().firstOrNull { it.id == preset })
    }

    @Test
    fun renamePresetKeepsPresetIdentity() = runBlocking {
        val preset = repository.selectableTags.first().first { it.preset }.id
        repository.renameTag(preset, "我的经历")
        val after = repository.selectableTags.first().first { it.id == preset }
        assertEquals("我的经历", after.name)
        // 改名不改变预置身份，仍不可删除
        assertTrue(after.preset)
        assertTrue(runCatching { repository.deleteTag(preset) }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun saveKeepsCustomTagAlongsidePreset() = runBlocking {
        val custom = repository.createTag("读书")
        val preset = presetId(0)
        val entry = repository.save(draft("两件都占", listOf(custom, preset)))
        // 归一化后预置在前、自定义在后
        assertEquals(listOf(preset, custom), repository.detail(entry)!!.tagIds)
    }
}
