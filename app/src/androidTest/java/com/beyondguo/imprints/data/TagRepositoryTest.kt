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
 * 标签体系（真机/模拟器内存库）：
 * 覆盖 Room 映射、保存归一化、外键级联与时间线筛选所依赖的关联查询。
 */
@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {
    private lateinit var db: ImprintDatabase
    private lateinit var repository: ImprintRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImprintDatabase::class.java).build()
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

    @Test
    fun presetTagsArePersistedBySeedPath() = runBlocking {
        // 内存库由 Room 直接建表，不跑迁移；这里显式落 seed，验证实体映射与查询可用
        db.dao().insertTags(TagCatalog.seed)
        val stored = repository.tags.first()
        assertEquals(TagCatalog.PRESETS.size, stored.size)
        assertEquals(TagCatalog.selectableIds, stored.sortedBy { it.ordinal }.map { it.id })
        assertTrue(stored.none { it.archived })
    }

    @Test
    fun saveAttachesTagsAndDetailReturnsThemInCatalogOrder() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val a = TagCatalog.selectableIds[0]
        val b = TagCatalog.selectableIds[1]
        val id = repository.save(draft("同时挂两个标签", listOf(b, a)))
        // detail 按目录顺序归一化，与传入顺序无关
        assertEquals(listOf(a, b), repository.detail(id)!!.tagIds)
        assertEquals(
            listOf(a, b),
            repository.entryTags.first().filter { it.entryId == id }.map { it.tagId }.sortedBy { TagCatalog.selectableIds.indexOf(it) },
        )
    }

    @Test
    fun saveReplacesTagsInsteadOfAppending() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val a = TagCatalog.selectableIds[0]
        val b = TagCatalog.selectableIds[1]
        val id = repository.save(draft("第一版", listOf(a)))
        repository.save(draft("第二版", listOf(b)).copy(id = id))
        assertEquals(listOf(b), repository.detail(id)!!.tagIds)
    }

    @Test
    fun unknownAndDuplicateTagsAreDropped() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val a = TagCatalog.selectableIds[0]
        val id = repository.save(draft("脏数据", listOf(a, a, "not-a-tag", "")))
        assertEquals(listOf(a), repository.detail(id)!!.tagIds)
    }

    @Test
    fun saveWithoutTagsLeavesNoRows() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val id = repository.save(draft("没有标签"))
        assertTrue(repository.detail(id)!!.tagIds.isEmpty())
        assertTrue(repository.entryTags.first().none { it.entryId == id })
    }

    @Test
    fun timelineFilterMatchesSelectedTags() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val personal = TagCatalog.selectableIds[0]
        val national = TagCatalog.selectableIds[1]
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
        db.dao().insertTags(TagCatalog.seed)
        val id = repository.save(draft("会被彻底删除", listOf(TagCatalog.selectableIds[0])))
        repository.deletePermanently(id)
        // 外键 CASCADE：不留孤儿关联行
        assertTrue(repository.entryTags.first().none { it.entryId == id })
        // 标签本身保留，供后续印迹继续使用
        assertEquals(TagCatalog.PRESETS.size, repository.tags.first().size)
    }

    @Test
    fun movingToRecycleKeepsTagsForRestore() = runBlocking {
        db.dao().insertTags(TagCatalog.seed)
        val tag = TagCatalog.selectableIds[0]
        val id = repository.save(draft("进回收站", listOf(tag)))
        repository.moveToRecycle(id)
        repository.restore(id)
        assertEquals(listOf(tag), repository.detail(id)!!.tagIds)
    }
}
