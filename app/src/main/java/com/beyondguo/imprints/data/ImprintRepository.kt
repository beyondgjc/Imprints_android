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

sealed interface MediaChoice {
    data class Existing(val value: VisualMediaEntity) : MediaChoice
    data class New(val value: PreparedMedia) : MediaChoice
}

sealed interface AudioChoice {
    data class Existing(val value: AudioEntity) : AudioChoice
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
)

data class EntryDetail(
    val entry: EntryEntity,
    val visuals: List<VisualMediaEntity>,
    val audio: AudioEntity?,
    val feeling: FeelingEntity?,
    val place: PlaceEntity?,
)

class ImprintRepository(
    private val database: ImprintDatabase,
    val files: MediaFiles,
    val security: ImprintSecurity,
) {
    private val dao = database.dao()
    val active: Flow<List<EntryEntity>> = dao.activeEntries()
    val recycled: Flow<List<EntryEntity>> = dao.recycledEntries()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutation.withLock { block() }

    suspend fun detail(id: String): EntryDetail? {
        val entry = dao.entry(id) ?: return null
        return EntryDetail(entry, dao.visuals(id), dao.audio(id), dao.feeling(id), entry.placeId?.let { dao.place(it) })
    }

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
        val committedNew = mutableListOf<String>()
        try {
            val visuals = draft.visuals.mapIndexed { ordinal, choice ->
                when (choice) {
                    is MediaChoice.Existing -> {
                        check(oldVisuals.any { it.id == choice.value.id }) { "媒体引用无效" }
                        choice.value.copy(ordinal = ordinal)
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
                    check(oldAudio?.id == choice.value.id) { "音频引用无效" }
                    choice.value
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
            }
            val retained = visuals.flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) }.toSet() +
                listOfNotNull(audio?.relativePath)
            (oldVisuals.flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) } +
                listOfNotNull(oldAudio?.relativePath))
                .filter { it !in retained }.forEach { path -> runCatching { files.resolve(path).delete() } }
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
        if (entry.deletedAtMs == null) dao.upsertEntry(entry.copy(deletedAtMs = System.currentTimeMillis()))
    }

    /** 恢复回收站条目；不在窗口内或不存在时抛出可提示的错误。 */
    suspend fun restore(id: String) = mutation.withLock {
        val entry = dao.entry(id) ?: error("印迹已不存在")
        check(entry.deletedAtMs != null) { "印迹不在回收站" }
        check(EntryRules.isRestorable(entry.deletedAtMs, System.currentTimeMillis())) { "已超过 7 天保留期，无法恢复" }
        dao.upsertEntry(entry.copy(deletedAtMs = null))
    }

    suspend fun deletePermanently(id: String) = mutation.withLock { deletePermanentlyLocked(id) }

    private suspend fun deletePermanentlyLocked(id: String) {
        val entry = dao.entry(id) ?: return
        val paths = dao.visuals(id).flatMap { listOf(it.relativePath) + listOfNotNull(it.motionVideoPath) } +
            listOfNotNull(dao.audio(id)?.relativePath)
        database.withTransaction {
            dao.deleteFts(id)
            dao.deleteEntry(id)
        }
        paths.forEach { files.resolve(it).delete() }
        entry.placeId?.let { dao.deleteUnusedPlace(it) }
    }

    suspend fun cleanupExpired() = mutation.withLock {
        dao.expired(EntryRules.recycleCutoff(System.currentTimeMillis())).forEach { deletePermanentlyLocked(it.id) }
        val refs = dao.referencedFiles().toSet() + dao.referencedMotionVideos().toSet()
        files.cleanup(refs)
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
