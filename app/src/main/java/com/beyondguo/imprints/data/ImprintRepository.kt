package com.beyondguo.imprints.data

import androidx.room.withTransaction
import com.beyondguo.imprints.security.ImprintSecurity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 编辑期的媒体选择：已落盘的（编辑已有内容时）或刚选进来还没提交的。
 * **Existing 持有的是 [MediaItem]**，不是某个实体 —— 媒体项本身与业务无关。
 */
sealed interface MediaChoice {
    data class Existing(val value: MediaItem) : MediaChoice
    data class New(val value: PreparedMedia) : MediaChoice
}

sealed interface AudioChoice {
    data class Existing(val value: MediaItem) : AudioChoice
    data class New(val value: PreparedMedia) : AudioChoice
}

data class EntryDraft(
    val id: String? = null,
    val body: String = "",
    val eventLocalMinute: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")),
    val eventZoneId: String = ZoneId.systemDefault().id,
    val visuals: List<MediaChoice> = emptyList(),
    val audio: AudioChoice? = null,
    val feeling: String = "",
    val place: PlaceEntity? = null,
    /** 预置标签 id 集合；保存时按 TagCatalog 归一化（丢弃未知 id、去重、按目录排序）。 */
    val tagIds: List<String> = emptyList(),
)

data class EntryDetail(
    val entry: EntryEntity,
    val visuals: List<VisualMediaEntity>,
    val audio: AudioEntity?,
    val feeling: FeelingEntity?,
    val place: PlaceEntity?,
    val tagIds: List<String> = emptyList(),
)

class ImprintRepository(
    private val database: ImprintDatabase,
    val files: MediaFiles,
    val security: ImprintSecurity,
) {
    private val dao = database.dao()
    private val mailDao = database.mailReviewDao()
    val active: Flow<List<EntryEntity>> = dao.activeEntries()
    val recycled: Flow<List<EntryEntity>> = dao.recycledEntries()
    /** 预置标签表；时间线筛选栏与编辑页选项都来自这里。 */
    val tags: Flow<List<TagEntity>> = dao.allTags()
    /** 时间线筛选栏/编辑页可选的标签（不含已停用）。 */
    val selectableTags: Flow<List<TagEntity>> = dao.activeTags()
    /** 印迹 ↔ 标签关联，供时间线在内存里筛选。 */
    val entryTags: Flow<List<EntryTagEntity>> = dao.allEntryTags()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutation.withLock { block() }

    /**
     * 普通印迹详情。**信件 id 一律返回 null**：信件正文只经信件安全流程读取
     * （`LetterRepository.letterDetail`），不能借普通详情路由直接渲染。
     */
    suspend fun detail(id: String): EntryDetail? {
        if (mailDao.isLetter(id)) return null
        val entry = dao.entry(id) ?: return null
        return EntryDetail(
            entry, dao.visuals(id), dao.audio(id), dao.feeling(id), entry.placeId?.let { dao.place(it) },
            TagRules.normalizeSelection(dao.tagsOnce(), dao.tagsOf(id)),
        )
    }

    /** 对象类型判定：普通印迹 / 未来信 / 总结 / 不存在。所有详情路由都先过这里。 */
    suspend fun contentKind(id: String): ContentKind? = when {
        dao.entry(id) != null -> if (mailDao.isLetter(id)) ContentKind.LETTER else ContentKind.ENTRY
        mailDao.review(id) != null -> ContentKind.REVIEW
        else -> null
    }

    /**
     * 当前仍被引用的全部媒体文件：
     * entries/信件的 visual_media + audio、总结快照的 review_source_media、
     * 以及 media_references（草稿与快照所有权）。**物理文件只有不被任何一方引用时才可删。**
     */
    suspend fun referencedPaths(): Set<String> =
        dao.referencedFiles().toSet() + dao.referencedMotionVideos().toSet() +
            mailDao.mediaRefPaths().toSet() + mailDao.reviewMediaPaths().toSet()

    /** 删除已被替换/清除的附件，但先确认没有其它快照、草稿或对象还在引用。 */
    suspend fun deleteFilesIfUnreferenced(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val refs = referencedPaths()
        paths.filter { it !in refs }.forEach { path ->
            android.util.Log.w("MediaCleanup", "deleteFileIfUnreferenced $path refs=${refs.size}")
            runCatching { files.resolve(path).delete() }
        }
    }

    /** 登记某个对象对一组文件的所有权（整体替换语义）。 */
    suspend fun registerMediaOwner(ownerKind: String, ownerId: String, paths: Collection<String>) {
        mailDao.deleteMediaRefs(ownerKind, ownerId)
        if (paths.isNotEmpty()) {
            mailDao.insertMediaRefs(paths.map { MediaReferenceEntity(ownerKind, ownerId, it) })
        }
    }

    /** 当前可用于新建标签的余量判断依据。 */
    suspend fun activeTagCount(): Int = dao.activeTagCount()

    /**
     * 新建自定义标签。名称已存在（忽略首尾空白、大小写与全半角）时**不新建**，
     * 直接返回已有标签的 id，由调用方把它选中——用户在编辑页想建一个已存在的标签时，
     * 期望的是「用上它」而不是看到一条报错。
     * 名称规则与数量上限见 [TagRules]，真正需要新建但校验不过时才抛错。
     */
    suspend fun createTag(rawName: String): String = mutation.withLock {
        val name = TagRules.normalizeName(rawName)
        val existing = dao.tagsOnce()
        TagRules.findByName(existing, name)?.let { return@withLock it.id }
        TagRules.validationError(existing, name)?.let { throw IllegalArgumentException(it) }
        if (TagRules.hasReachedLimit(dao.activeTagCount())) {
            throw IllegalStateException("标签最多 ${TagRules.MAX_TAG_COUNT} 个，先删掉不用的再新建")
        }
        val id = UUID.randomUUID().toString()
        dao.insertTag(TagEntity(id = id, name = name, ordinal = 0, preset = false, createdAtMs = System.currentTimeMillis()))
        id
    }

    /** 改名；预置标签也可以改名（用户可见文案），但 id 不变、身份仍是预置、不可删除。 */
    suspend fun renameTag(id: String, rawName: String) = mutation.withLock {
        val name = TagRules.normalizeName(rawName)
        TagRules.validationError(dao.tagsOnce(), name, editingId = id)?.let { throw IllegalArgumentException(it) }
        check(dao.tagsOnce().any { it.id == id }) { "标签已不存在" }
        dao.renameTag(id, name)
    }

    /** 删除标签：只解除与印迹的关联，不动印迹本体。预置标签不可删除。 */
    suspend fun deleteTag(id: String) = mutation.withLock {
        check(!TagCatalog.isPreset(id)) { "预置标签不可删除" }
        check(dao.tagsOnce().any { it.id == id }) { "标签已不存在" }
        database.withTransaction {
            dao.deleteEntryTagsForTag(id)
            dao.deleteTag(id)
        }
    }

    /** 删除前提示用：该标签已挂载的印迹条数。 */
    suspend fun entryCountForTag(id: String): Int = dao.entryCountForTag(id)

    /** 「常用标签」用：标签 id → 使用次数，按次数从多到少。 */
    suspend fun tagUsageCounts(): List<Pair<String, Int>> =
        dao.tagUsageCounts().sortedByDescending { it.count }.map { it.tagId to it.count }

    suspend fun save(draft: EntryDraft): String = mutation.withLock {
        val body = draft.body.trim()
        EntryRules.validateForSave(body, draft.visuals.size, draft.eventLocalMinute)
        ZoneId.of(draft.eventZoneId)
        val id = draft.id ?: UUID.randomUUID().toString()
        val old = dao.entry(id)
        if (draft.id != null) check(old != null && old.deletedAtMs == null) { "原印迹已不存在" }
        val oldVisuals = dao.visuals(id)
        val oldAudio = dao.audio(id)
        val now = System.currentTimeMillis()
        val feeling = security.encodeFeeling(id, draft.feeling)
        val tagIds = TagRules.normalizeSelection(dao.tagsOnce(), draft.tagIds)
        val committedNew = mutableListOf<String>()
        try {
            val visuals = draft.visuals.mapIndexed { ordinal, choice ->
                when (choice) {
                    is MediaChoice.Existing -> {
                        // MediaItem 不知道自己属于谁，所以用路径在原实体里找回来（顺序/归属以库为准）
                        val old = oldVisuals.firstOrNull { it.relativePath == choice.value.path }
                        check(old != null) { "媒体引用无效" }
                        old.copy(ordinal = ordinal)
                    }
                    is MediaChoice.New -> {
                        check(choice.value.kind in setOf("IMAGE", "GIF", "VIDEO"))
                        val relativePath = files.commit(choice.value)
                        committedNew += relativePath
                        choice.value.motionVideoId?.let { mid ->
                            committedNew += mid
                        }
                        VisualMediaEntity(choice.value.id, id, choice.value.kind, ordinal, relativePath,
                            choice.value.mime, choice.value.byteLength, choice.value.sha256, now,
                            motionVideoPath = choice.value.motionVideoId,
                            motionVideoSha256 = choice.value.motionVideoSha256,
                            motionVideoByteLength = choice.value.motionVideoByteLength)
                    }
                }
            }
            val audio = when (val choice = draft.audio) {
                null -> null
                is AudioChoice.Existing -> {
                    check(oldAudio?.relativePath == choice.value.path) { "音频引用无效" }
                    oldAudio
                }
                is AudioChoice.New -> {
                    check(choice.value.kind == "AUDIO")
                    val relativePath = files.commit(choice.value)
                    committedNew += relativePath
                    AudioEntity(id, choice.value.id, relativePath, choice.value.mime, choice.value.byteLength, choice.value.sha256)
                }
            }
            val place = draft.place
            database.withTransaction {
                if (place != null) dao.upsertPlace(place)
                dao.upsertEntry(EntryEntity(id, body, draft.eventLocalMinute, draft.eventZoneId,
                    old?.createdAtMs ?: now, now, null, place?.id))
                dao.deleteVisuals(id)
                if (visuals.isNotEmpty()) dao.insertVisuals(visuals)
                dao.deleteAudio(id)
                if (audio != null) dao.upsertAudio(audio)
                dao.deleteFeeling(id)
                if (feeling != null) dao.upsertFeeling(feeling)
                dao.deleteFts(id)
                if (body.isNotBlank()) dao.insertFts(EntryFtsEntity(entryId = id, body = body))
                // 标签整体替换：多挂/少挂都在这里一次性对齐
                dao.deleteEntryTags(id)
                if (tagIds.isNotEmpty()) dao.insertEntryTags(tagIds.map { EntryTagEntity(id, it) })
                // 附件所有权：普通印迹名下的文件集合（总结快照/草稿另记，替换附件不得误删）
                val ownedPaths = visuals.flatMap { listOfNotNull(it.relativePath, it.motionVideoPath) } +
                    listOfNotNull(audio?.relativePath)
                registerMediaOwner(OWNER_ENTRY, id, ownedPaths)
            }
            val retained = visuals.flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) }.toSet() +
                listOfNotNull(audio?.relativePath)
            // 被替换掉的附件只有在没有快照/草稿等其它引用时才物理删除
            deleteFilesIfUnreferenced(
                (oldVisuals.flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) } +
                    listOfNotNull(oldAudio?.relativePath)).filter { it !in retained },
            )
            old?.placeId?.takeIf { it != place?.id }?.let { oldPlace ->
                runCatching { dao.deleteUnusedPlace(oldPlace) }
            }
            id
        } catch (error: Exception) {
            committedNew.forEach { files.resolve(it).delete() }
            throw error
        }
    }

    suspend fun moveToRecycle(id: String) = mutation.withLock {
        val entry = dao.entry(id) ?: return@withLock
        if (entry.deletedAtMs == null) {
            dao.upsertEntry(entry.copy(deletedAtMs = System.currentTimeMillis()))
            // 向总结传播：来源进回收站 → 快照原文/媒体/封面/统计贡献立即隐藏
            mailDao.onSourceRecycled(if (mailDao.isLetter(id)) SourceKind.LETTER.name else SourceKind.ENTRY.name, id)
        }
    }

    /** 恢复回收站条目；不在窗口内或不存在时抛出可提示的错误。 */
    suspend fun restore(id: String) = mutation.withLock {
        val entry = dao.entry(id) ?: error("印迹已不存在")
        check(entry.deletedAtMs != null) { "印迹不在回收站" }
        check(EntryRules.isRestorable(entry.deletedAtMs, System.currentTimeMillis())) { "已超过 7 天保留期，无法恢复" }
        dao.upsertEntry(entry.copy(deletedAtMs = null))
        mailDao.onSourceRestored(if (mailDao.isLetter(id)) SourceKind.LETTER.name else SourceKind.ENTRY.name, id)
    }

    suspend fun deletePermanently(id: String) = mutation.withLock { deletePermanentlyLocked(id) }

    private suspend fun deletePermanentlyLocked(id: String) {
        val entry = dao.entry(id) ?: return
        val sourceKind = if (mailDao.isLetter(id)) SourceKind.LETTER.name else SourceKind.ENTRY.name
        val paths = dao.visuals(id).flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) } +
            listOfNotNull(dao.audio(id)?.relativePath)
        // 向总结传播彻底删除：快照正文与媒体引用清掉，保留无内容占位；返回被释放的文件
        val released = mailDao.onSourcePurged(sourceKind, id)
        database.withTransaction {
            dao.deleteFts(id)
            dao.deleteEntry(id) // CASCADE：future_letters / notification events / visuals / audio / feelings / entry_tags
            mailDao.deleteMediaRefs(OWNER_ENTRY, id)
            mailDao.deleteMediaRefs(OWNER_LETTER, id)
        }
        // 物理文件只在没有任何快照/草稿/对象引用时才删
        deleteFilesIfUnreferenced(paths + released)
        entry.placeId?.let { dao.deleteUnusedPlace(it) }
    }

    suspend fun cleanupExpired() = mutation.withLock {
        dao.expired(EntryRules.recycleCutoff(System.currentTimeMillis())).forEach { deletePermanentlyLocked(it.id) }
        files.cleanup(referencedPaths())
    }

    suspend fun searchBody(input: String): List<EntryEntity> {
        val safe = EntryRules.buildBodySearchQuery(input) ?: return emptyList()
        val indexed = dao.searchBody(safe)
        return if (indexed.isNotEmpty()) indexed else dao.searchBodySubstring(input.trim())
    }

    companion object {
        const val SEVEN_DAYS_MS = EntryRules.RETENTION_MS
        private val mutation = Mutex()
    }
}
