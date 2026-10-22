package com.beyondguo.imprints.data

import androidx.room.withTransaction
import com.beyondguo.imprints.mail.LetterRules
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 信件附件记录：文件**已提交**到应用私有 media 目录（UUID 文件名）。
 * 草稿、已封存信件、编辑中间态共用这一形态；实体行在封存时才写入 visual_media / audio。
 */
data class LetterAttachment(
    val path: String,
    val kind: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    val motionPath: String? = null,
    val motionSha256: String? = null,
    val motionByteLength: Long? = null,
) {
    fun allPaths(): List<String> = listOfNotNull(path, motionPath)

    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("kind", kind).put("mime", mime)
        .put("byteLength", byteLength).put("sha256", sha256)
        .put("motionPath", motionPath ?: "").put("motionSha256", motionSha256 ?: "")
        .put("motionByteLength", motionByteLength ?: 0L)

    companion object {
        fun fromJson(j: JSONObject) = LetterAttachment(
            path = j.getString("path"),
            kind = j.getString("kind"),
            mime = j.getString("mime"),
            byteLength = j.getLong("byteLength"),
            sha256 = j.getString("sha256"),
            motionPath = j.optString("motionPath").takeIf { it.isNotEmpty() },
            motionSha256 = j.optString("motionSha256").takeIf { it.isNotEmpty() },
            motionByteLength = j.optLong("motionByteLength").takeIf { it > 0 },
        )
    }
}

/**
 * 写信编辑器的数据形态（ML-01）。
 * [id] 非空 = 编辑已封存的信（保留原送达计划与首次写信时间）；
 * [draftId] 非空 = 从内容草稿恢复/保存。
 */
data class LetterDraft(
    val draftId: String? = null,
    val id: String? = null,
    val title: String = "",
    val body: String = "",
    val visuals: List<LetterAttachment> = emptyList(),
    val audio: LetterAttachment? = null,
    val deliverLocalMinute: String = "",
    val deliverZoneId: String = ZoneId.systemDefault().id,
    val notifyEnabled: Boolean = true,
)

/** 信件正文页数据：正文与附件按需读取（信封列表只投影标题与日期）。 */
data class LetterDetail(
    val letter: FutureLetterEntity,
    val entry: EntryEntity,
    val visuals: List<VisualMediaEntity>,
    val audio: AudioEntity?,
)

/**
 * 时光邮箱的持久化与事务（ML-01 ~ ML-03、ML-08）。
 *
 * 所有写操作走 [ImprintRepository.exclusive] 互斥区；附件失败整体回滚；
 * 删除/恢复向总结传播可见性（[onSourceRecycled] / [onSourceRestored] / [onSourcePurged]）。
 * 调度（AlarmManager/WorkManager）不在这层：数据库是事实来源，调度可重建。
 */
class LetterRepository(
    private val database: ImprintDatabase,
    val files: MediaFiles,
    val core: ImprintRepository,
) {
    private val dao = database.dao()
    private val mailDao = database.mailReviewDao()
    private val minuteFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    val sealedLetters: Flow<List<LetterListRow>> = mailDao.sealedLetters()
    val recycledLetters: Flow<List<LetterListRow>> = mailDao.recycledLetters()
    /**
     * 草稿箱：**只含独立未封存草稿**（`targetId` 为空）。
     * 关联已封存信的旧编辑草稿不得作为续写入口，所以不走 [mailDao.drafts] 的全量查询。
     */
    val standaloneDrafts: Flow<List<ContentDraftEntity>> = mailDao.standaloneDrafts(DRAFT_TYPE_LETTER)
    /**
     * 封存管理页：仅日期的最小投影，不含标题／正文／附件。
     * 已确认到期的行由 DAO 过滤掉；尚未确认但已过时刻的行由界面按 now 再滤。
     */
    val sealedMetas: Flow<List<SealedLetterMeta>> = mailDao.sealedLetterMetas()
    val reviewDrafts: Flow<List<ContentDraftEntity>> = mailDao.drafts(DRAFT_TYPE_REVIEW)

    suspend fun letterRow(id: String): LetterListRow? =
        sealedLettersList().firstOrNull { it.entryId == id } ?: recycledLettersList().firstOrNull { it.entryId == id }

    private suspend fun sealedLettersList(): List<LetterListRow> = mailDao.sealedLettersList()
    private suspend fun recycledLettersList(): List<LetterListRow> = mailDao.recycledLettersList()

    suspend fun letter(id: String): FutureLetterEntity? = mailDao.letter(id)

    /**
     * 信件正文与附件。**这里是数据层硬边界**：未到期或已进回收站一律返回 null。
     *
     * 不能只在 UI 判断 —— 通知点击、旧路由、重建的 Activity、进程恢复、缓存与附件读取
     * 都可能绕过界面条件，必须在 Repository 收口（V02-03）。
     */
    suspend fun letterDetail(id: String, nowMs: Long = System.currentTimeMillis()): LetterDetail? {
        val letter = mailDao.letter(id) ?: return null
        val entry = dao.entry(id) ?: return null
        if (entry.deletedAtMs != null) return null
        if (!LetterRules.isDue(letter, nowMs)) return null
        return LetterDetail(letter, entry, dao.visuals(id), dao.audio(id))
    }

    // ---------- 附件（编辑器） ----------

    /** 把待加入附件提交进 media 目录并登记到编辑会话名下，避免被垃圾回收误删。 */
    suspend fun commitAttachment(prepared: PreparedMedia, sessionOwnerId: String): LetterAttachment {
        val path = files.commit(prepared)
        val attachment = LetterAttachment(
            path = path,
            kind = prepared.kind,
            mime = prepared.mime,
            byteLength = prepared.byteLength,
            sha256 = prepared.sha256,
            motionPath = prepared.motionVideoId?.takeIf { files.resolve(it).isFile },
            motionSha256 = prepared.motionVideoId?.let { prepared.motionVideoSha256 },
            motionByteLength = prepared.motionVideoId?.let { prepared.motionVideoByteLength },
        )
        mailDao.insertMediaRefs(attachment.allPaths().map { MediaReferenceEntity(OWNER_DRAFT, sessionOwnerId, it) })
        return attachment
    }

    /** 移除编辑会话里的某个附件（未保存前）。文件仅在无人引用时物理删除。 */
    suspend fun releaseAttachment(sessionOwnerId: String, attachment: LetterAttachment) = core.exclusive {
        attachment.allPaths().forEach { path ->
            mailDao.deleteMediaRef(OWNER_DRAFT, sessionOwnerId, path)
        }
        core.deleteFilesIfUnreferenced(attachment.allPaths())
    }

    /** 放弃整个编辑会话：释放会话名下的所有未保存附件。 */
    suspend fun releaseSession(sessionOwnerId: String) = core.exclusive {
        val paths = mailDao.mediaRefPathsOwnedBy(OWNER_DRAFT, sessionOwnerId)
        mailDao.deleteMediaRefs(OWNER_DRAFT, sessionOwnerId)
        core.deleteFilesIfUnreferenced(paths)
    }

    // ---------- 草稿 ----------

    /** 保存草稿：不触发任务、不计入未读；附件所有权记在草稿名下。 */
    suspend fun saveDraft(draftId: String, draft: LetterDraft) = core.exclusive {
        // V0.2：草稿只服务独立未封存稿；不能借草稿给已封存的信留一条可编辑的尾巴
        require(draft.id == null) { "已封存的信不能作为草稿继续编辑" }
        val payload = JSONObject()
            .put("title", draft.title)
            .put("body", draft.body)
            .put("deliverLocalMinute", draft.deliverLocalMinute)
            .put("deliverZoneId", draft.deliverZoneId)
            .put("notifyEnabled", draft.notifyEnabled)
            .put("visuals", JSONArray().apply { draft.visuals.forEach { put(it.toJson()) } })
            .put("audio", draft.audio?.toJson() ?: JSONObject.NULL)
        mailDao.upsertDraft(
            ContentDraftEntity(
                id = draftId,
                type = DRAFT_TYPE_LETTER,
                targetId = draft.id,
                payload = payload.toString(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        val paths = draft.visuals.flatMap { it.allPaths() } + draft.audio?.allPaths().orEmpty()
        core.registerMediaOwner(OWNER_DRAFT, draftId, paths)
    }

    /**
     * 读取草稿。V0.2 起**只认独立未封存草稿**：
     * 关联已封存信的旧编辑草稿（`targetId` 非空）不得再被恢复成可读编辑器，直接返回 null，
     * 也不参与草稿箱列表（见 [standaloneDrafts]）。
     */
    suspend fun draft(id: String): LetterDraft? =
        mailDao.draft(id)?.takeIf { it.targetId == null }?.let(::parseDraft)

    private fun parseDraft(entity: ContentDraftEntity): LetterDraft {
        val payload = JSONObject(entity.payload)
        val visuals = payload.optJSONArray("visuals")?.let { array ->
            (0 until array.length()).map { LetterAttachment.fromJson(array.getJSONObject(it)) }
        }.orEmpty()
        val audioJson = payload.opt("audio")
        val audio = (audioJson as? JSONObject)?.let { LetterAttachment.fromJson(it) }
        return LetterDraft(
            draftId = entity.id,
            id = entity.targetId,
            title = payload.optString("title"),
            body = payload.optString("body"),
            visuals = visuals,
            audio = audio,
            deliverLocalMinute = payload.optString("deliverLocalMinute"),
            deliverZoneId = payload.optString("deliverZoneId").ifBlank { ZoneId.systemDefault().id },
            notifyEnabled = payload.optBoolean("notifyEnabled", true),
        )
    }

    /** 删除草稿及其独占附件。 */
    suspend fun deleteDraft(draftId: String) = core.exclusive {
        val paths = mailDao.mediaRefPathsOwnedBy(OWNER_DRAFT, draftId)
        mailDao.deleteDraft(draftId)
        mailDao.deleteMediaRefs(OWNER_DRAFT, draftId)
        core.deleteFilesIfUnreferenced(paths)
    }

    // ---------- 封存 / 改期 ----------

    /**
     * 封存（新建）或重新封存（编辑）。
     * 新建：送达时间必须晚于当前时刻；编辑：保留原送达计划与首次写信时间，更新修改时间。
     * 成功后草稿被消费（删除草稿行与草稿所有权）。
     */
    suspend fun seal(draft: LetterDraft): String = core.exclusive {
        // V0.2：封存后正文与附件冻结，首版不提供编辑 —— 只允许新建（draft.id 必须为空）。
        // 下面的 editing/existing 分支仅为兼容旧调用形态保留，本检查之后不可达。
        require(draft.id == null) { "封存后的信件不能编辑；如需重写，请在设置中删除后重新写信" }
        val body = draft.body.trim()
        LetterRules.validateForSeal(body, draft.visuals.size)
        val now = System.currentTimeMillis()
        val editing = draft.id
        val existing = editing?.let { mailDao.letter(it) }
        if (editing != null) check(existing != null) { "信件已不存在" }

        val resolution = if (existing == null) {
            require(draft.deliverLocalMinute.isNotBlank()) { "请选择送达时间" }
            LetterRules.resolveDeliver(draft.deliverLocalMinute, draft.deliverZoneId).also {
                require(it.instantMs > now) { "送达时间必须晚于当前时刻" }
            }
        } else null

        val entryId = editing ?: UUID.randomUUID().toString()
        val oldVisuals = dao.visuals(entryId)
        val oldAudio = dao.audio(entryId)
        val oldEntry = dao.entry(entryId)
        val deliverAtMs = existing?.deliverAtMs ?: resolution!!.instantMs
        val deliverLocalMinute = existing?.deliverLocalMinute ?: draft.deliverLocalMinute
        val deliverZoneId = existing?.deliverZoneId ?: draft.deliverZoneId

        val visuals = draft.visuals.mapIndexed { ordinal, a ->
            require(files.resolve(a.path).isFile) { "附件已丢失，请重新添加" }
            VisualMediaEntity(
                id = a.path, entryId = entryId, kind = a.kind, ordinal = ordinal, relativePath = a.path,
                mime = a.mime, byteLength = a.byteLength, sha256 = a.sha256, createdAtMs = now,
                motionVideoPath = a.motionPath, motionVideoSha256 = a.motionSha256, motionVideoByteLength = a.motionByteLength,
            )
        }
        val audio = draft.audio?.let { a ->
            require(files.resolve(a.path).isFile) { "附件已丢失，请重新添加" }
            AudioEntity(entryId, a.path, a.path, a.mime, a.byteLength, a.sha256)
        }
        database.withTransaction {
            dao.upsertEntry(
                EntryEntity(
                    id = entryId,
                    body = body,
                    eventLocalMinute = oldEntry?.eventLocalMinute
                        ?: LocalDateTime.now().format(minuteFormat),
                    eventZoneId = oldEntry?.eventZoneId ?: ZoneId.systemDefault().id,
                    createdAtMs = oldEntry?.createdAtMs ?: now,
                    updatedAtMs = now,
                    deletedAtMs = null,
                    placeId = null,
                ),
            )
            dao.deleteVisuals(entryId)
            if (visuals.isNotEmpty()) dao.insertVisuals(visuals)
            dao.deleteAudio(entryId)
            if (audio != null) dao.upsertAudio(audio)
            // 信件不进正文 FTS：搜索只查普通印迹，未到期信更不能被搜出来
            dao.deleteFts(entryId)
            mailDao.upsertLetter(
                FutureLetterEntity(
                    entryId = entryId,
                    title = LetterRules.normalizeTitle(draft.title),
                    deliverAtMs = deliverAtMs,
                    deliverLocalMinute = deliverLocalMinute,
                    deliverZoneId = deliverZoneId,
                    deliveredAtMs = existing?.deliveredAtMs,
                    firstOpenedAtMs = existing?.firstOpenedAtMs,
                    deliveryReadAtMs = existing?.deliveryReadAtMs,
                    notifyEnabled = draft.notifyEnabled,
                    scheduleRevision = (existing?.scheduleRevision ?: 0) + 1,
                ),
            )
            // 所有权收口：信件名下 = 最终附件集合
            val finalPaths = visuals.flatMap { listOfNotNull(it.relativePath, it.motionVideoPath) } +
                listOfNotNull(audio?.relativePath)
            core.registerMediaOwner(OWNER_LETTER, entryId, finalPaths)
        }
        val stale = (oldVisuals.flatMap { listOfNotNull(it.relativePath, it.motionVideoPath) } +
            listOfNotNull(oldAudio?.relativePath)).toSet() -
            (visuals.flatMap { listOfNotNull(it.relativePath, it.motionVideoPath) } + listOfNotNull(audio?.relativePath)).toSet()
        core.deleteFilesIfUnreferenced(stale)
        draft.draftId?.let { discardDraftRows(it) }
        entryId
    }

    /** 改期（ML-02）：只修改未来时间；撤销旧调度并创建新调度版本由调度层负责。 */
    suspend fun reschedule(id: String, deliverLocalMinute: String, deliverZoneId: String) = core.exclusive {
        val letter = mailDao.letter(id) ?: error("信件已不存在")
        val entry = dao.entry(id) ?: error("信件已不存在")
        check(entry.deletedAtMs == null) { "回收站中的信件不能改期" }
        check(letter.deliveredAtMs == null && System.currentTimeMillis() < letter.deliverAtMs) {
            "已到期的信不能重新设为未到期，可另写一封新信"
        }
        val resolution = LetterRules.resolveDeliver(deliverLocalMinute, deliverZoneId)
        require(resolution.instantMs > System.currentTimeMillis()) { "送达时间必须晚于当前时刻" }
        mailDao.upsertLetter(
            letter.copy(
                deliverAtMs = resolution.instantMs,
                deliverLocalMinute = deliverLocalMinute,
                deliverZoneId = deliverZoneId,
                scheduleRevision = letter.scheduleRevision + 1,
            ),
        )
    }

    private suspend fun discardDraftRows(draftId: String) {
        val paths = mailDao.mediaRefPathsOwnedBy(OWNER_DRAFT, draftId)
        mailDao.deleteDraft(draftId)
        mailDao.deleteMediaRefs(OWNER_DRAFT, draftId)
        core.deleteFilesIfUnreferenced(paths)
    }

    // ---------- 到期 / 已读（ML-03） ----------

    /**
     * 到期后成功显示正文才算已读：记录送达已读并取消自动置顶。
     * 未到期、加载失败都不调用本方法。
     *
     * V0.2 起不再有「提前打开」这一维：`markEnvelopeOpened` 已删除，
     * `firstOpenedAtMs` 只作为历史字段保留，不参与路由、配色或已读判定。
     */
    suspend fun markDeliveryRead(id: String, nowMs: Long = System.currentTimeMillis()) = core.exclusive {
        val letter = mailDao.letter(id) ?: return@exclusive
        if (!LetterRules.isDue(letter, nowMs)) return@exclusive
        val delivered = letter.deliveredAtMs ?: letter.deliverAtMs
        if (letter.deliveryReadAtMs == null) {
            mailDao.upsertLetter(letter.copy(deliveredAtMs = delivered, deliveryReadAtMs = nowMs, firstOpenedAtMs = letter.firstOpenedAtMs ?: nowMs))
        }
    }

    /** 首次确认到期：持久化 deliveredAtMs，之后不因时间回拨回退。返回是否是本次新确认。 */
    suspend fun confirmDue(id: String): Boolean = core.exclusive {
        val letter = mailDao.letter(id) ?: return@exclusive false
        if (letter.deliveredAtMs != null) return@exclusive false
        val now = System.currentTimeMillis()
        if (now < letter.deliverAtMs) return@exclusive false
        mailDao.upsertLetter(letter.copy(deliveredAtMs = letter.deliverAtMs))
        true
    }

    /**
     * 对账当前到期状态（前台到点、冷启动、重启、时间变化都要调）。
     * 返回本次**新确认到期**的信件 id（用于决定要不要发通知）。
     */
    suspend fun refreshDeliveryState(nowMs: Long = System.currentTimeMillis()): List<String> = core.exclusive {
        val newlyDue = mutableListOf<String>()
        mailDao.allLetters().forEach { letter ->
            if (letter.deliveredAtMs == null && nowMs >= letter.deliverAtMs && dao.entry(letter.entryId)?.deletedAtMs == null) {
                mailDao.upsertLetter(letter.copy(deliveredAtMs = letter.deliverAtMs))
                newlyDue += letter.entryId
            }
        }
        newlyDue
    }

    /** 下一次送达瞬间（用于前台到点唤醒），没有待送达信时返回 null。 */
    suspend fun nextDeliveryAtMs(nowMs: Long = System.currentTimeMillis()): Long? =
        mailDao.upcomingLetters(nowMs).minOfOrNull { it.deliverAtMs }

    // ---------- 回收站（ML-08） ----------

    suspend fun moveToRecycle(id: String) = core.exclusive {
        val entry = dao.entry(id) ?: return@exclusive
        if (entry.deletedAtMs == null) {
            dao.upsertEntry(entry.copy(deletedAtMs = System.currentTimeMillis()))
            mailDao.onSourceRecycled(SourceKind.LETTER.name, id)
        }
    }

    suspend fun restore(id: String) = core.exclusive {
        val entry = dao.entry(id) ?: error("信件已不存在")
        check(entry.deletedAtMs != null) { "信件不在回收站" }
        check(EntryRules.isRestorable(entry.deletedAtMs, System.currentTimeMillis())) { "已超过 7 天保留期，无法恢复" }
        dao.upsertEntry(entry.copy(deletedAtMs = null))
        mailDao.onSourceRestored(SourceKind.LETTER.name, id)
    }

    suspend fun deletePermanently(id: String) = core.deletePermanently(id)

    companion object {
        const val DRAFT_TYPE_LETTER = "LETTER"
        const val DRAFT_TYPE_REVIEW = "REVIEW"
    }
}
