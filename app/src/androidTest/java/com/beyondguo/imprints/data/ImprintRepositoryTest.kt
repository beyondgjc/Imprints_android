package com.beyondguo.imprints.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Room + Repository：保存、搜索、回收站、彻底删除（真机内存库）。 */
@RunWith(AndroidJUnit4::class)
class ImprintRepositoryTest {
    private lateinit var db: ImprintDatabase
    private lateinit var repository: ImprintRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImprintDatabase::class.java)
            .build()
        repository = ImprintRepository(db, MediaFiles(context), ImprintSecurity(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(body: String, id: String? = null) = EntryDraft(
        id = id,
        body = body,
        eventLocalMinute = "2026-10-08T12:00",
    )

    @Test
    fun saveCreatesActiveEntryAndFts() = runBlocking {
        val id = repository.save(draft("在海边看日落，喝了一杯冰美式"))
        val detail = repository.detail(id)
        assertNotNull(detail)
        assertEquals("在海边看日落，喝了一杯冰美式", detail!!.entry.body)
        assertNull(detail.entry.deletedAtMs)

        val hits = repository.searchBody("日落")
        assertTrue(hits.any { it.id == id })

        // 单关键词走 FTS 或 instr 回退，均应命中
        assertTrue(repository.searchBody("冰美式").any { it.id == id })
        assertTrue(repository.searchBody("海边").any { it.id == id })
    }

    @Test
    fun saveRejectsEmptyBodyWithoutVisuals() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.save(draft("   ")) }
        }
    }

    @Test
    fun updateKeepsIdAndUpdatesBody() = runBlocking {
        val id = repository.save(draft("第一版"))
        val updated = repository.save(draft("第二版", id = id))
        assertEquals(id, updated)
        assertEquals("第二版", repository.detail(id)!!.entry.body)
        // FTS 同步：旧词不再命中
        assertTrue(repository.searchBody("第一版").none { it.id == id })
        assertTrue(repository.searchBody("第二版").any { it.id == id })
    }

    @Test
    fun recycleRestoreAndPermanentDelete() = runBlocking {
        val id = repository.save(draft("要进回收站的印迹"))

        repository.moveToRecycle(id)
        val recycled = repository.detail(id)!!.entry
        assertNotNull(recycled.deletedAtMs)
        assertTrue(repository.searchBody("回收站").none { it.id == id })

        repository.restore(id)
        val restored = repository.detail(id)!!.entry
        assertNull(restored.deletedAtMs)

        repository.moveToRecycle(id)
        repository.deletePermanently(id)
        assertNull(repository.detail(id))
    }

    @Test
    fun restoreRejectsMissingEntry() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.restore(UUID.randomUUID().toString()) }
        }
    }

    @Test
    fun searchWithBlankReturnsEmpty() = runBlocking {
        repository.save(draft("有内容"))
        assertTrue(repository.searchBody("   ").isEmpty())
    }

    @Test
    fun feelingStoredPlainWhenEncryptionOff() = runBlocking {
        val id = repository.save(EntryDraft(body = "带感受", feeling = "今天很开心"))
        val detail = repository.detail(id)!!
        assertNotNull(detail.feeling)
        assertEquals("PLAIN", detail.feeling!!.encoding)
        assertEquals("今天很开心", detail.feeling!!.payload)
        assertFalse(repository.searchBody("开心").any { it.id == id }) // 不索引感受
    }
}
