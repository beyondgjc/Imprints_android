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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 标签挂载与时间线筛选（真机/模拟器内存库）：
 * 覆盖 Room 映射、保存归一化、外键级联与筛选所依赖的关联查询。
 */
@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {
    private lateinit var db: ImprintDatabase
    private lateinit var repository: ImprintRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 与生产同一条建库路径：预置标签由 SEED_TAGS_ON_CREATE 落库
        db = Room.inMemoryDatabaseBuilder(context, ImprintDatabase::class.java)
            .addCallback(ImprintDatabase.SEED_TAGS_ON_CREATE)
            .build()
        repository = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(body: String, tags: List<String> = emptyList()) = EntryDraft(
        body = body,
        eventLocalMinute = "2026-10-08T12:00",
        tagIds = tags,
    )

    /** 预置标签 id 按 ordinal 取，避免测试硬编码具体中文名。 */
    private suspend fun presetId(index: Int) = db.dao().tagsOnce().first { it.preset && it.ordinal == index }.id

    @Test
    fun presetTagsAreAvailableOutOfTheBox() = runBlocking {
        val stored = repository.selectableTags.first()
        assertEquals(TagCatalog.PRESETS.size, stored.size)
        assertEquals(TagCatalog.PRESETS.map { it.id }, stored.map { it.id })
        assertTrue(stored.all { it.preset })
    }

    @Test
    fun saveAttachesTagsAndDetailReturnsThemInCatalogOrder() = runBlocking {
        val a = presetId(0)
        val b = presetId(1)
        val id = repository.save(draft("同时挂两个标签", listOf(b, a)))
        // detail 按目录顺序归一化，与传入顺序无关
        assertEquals(listOf(a, b), repository.detail(id)!!.tagIds)
        val attached = repository.entryTags.first().filter { it.entryId == id }.map { it.tagId }
        assertEquals(setOf(a, b), attached.toSet())
    }

    @Test
    fun saveReplacesTagsInsteadOfAppending() = runBlocking {
        val a = presetId(0)
        val b = presetId(1)
        val id = repository.save(draft("第一版", listOf(a)))
        repository.save(draft("第二版", listOf(b)).copy(id = id))
        assertEquals(listOf(b), repository.detail(id)!!.tagIds)
    }

    @Test
    fun unknownAndDuplicateTagsAreDropped() = runBlocking {
        val a = presetId(0)
        val id = repository.save(draft("脏数据", listOf(a, a, "not-a-tag", "")))
        assertEquals(listOf(a), repository.detail(id)!!.tagIds)
    }

    @Test
    fun saveWithoutTagsLeavesNoRows() = runBlocking {
        val id = repository.save(draft("没有标签"))
        assertTrue(repository.detail(id)!!.tagIds.isEmpty())
        assertTrue(repository.entryTags.first().none { it.entryId == id })
    }

    @Test
    fun timelineFilterMatchesSelectedTags() = runBlocking {
        val personal = presetId(0)
        val national = presetId(1)
        val untagged = repository.save(draft("自己的经历"))
        val mine = repository.save(draft("我的经历", listOf(personal)))
        val news = repository.save(draft("大事", listOf(national)))
        val active = repository.active.first()
        val byEntry = repository.entryTags.first().groupBy({ it.entryId }, { it.tagId })

        fun visible(selected: Set<String>) = active
            .filter { TagCatalog.matchesFilter(selected, byEntry[it.id].orEmpty()) }
            .map { it.id }
            .toSet()

        assertEquals(setOf(untagged, mine, news), visible(emptySet()))
        assertEquals(setOf(mine), visible(setOf(personal)))
        // OR 叠加：两个标签都选中时，无标签的印迹仍不出现
        assertEquals(setOf(mine, news), visible(setOf(personal, national)))
        assertFalse(untagged in visible(setOf(personal, national)))
    }

    @Test
    fun permanentDeleteCascadesEntryTags() = runBlocking {
        val id = repository.save(draft("会被彻底删除", listOf(presetId(0))))
        repository.deletePermanently(id)
        // 外键 CASCADE：不留孤儿关联行
        assertTrue(repository.entryTags.first().none { it.entryId == id })
        // 标签本身保留，供后续印迹继续使用
        assertEquals(TagCatalog.PRESETS.size, repository.selectableTags.first().size)
    }

    @Test
    fun movingToRecycleKeepsTagsForRestore() = runBlocking {
        val tag = presetId(0)
        val id = repository.save(draft("进回收站", listOf(tag)))
        repository.moveToRecycle(id)
        repository.restore(id)
        assertEquals(listOf(tag), repository.detail(id)!!.tagIds)
    }
}
