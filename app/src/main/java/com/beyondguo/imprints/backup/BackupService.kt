package com.beyondguo.imprints.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.beyondguo.imprints.data.AudioEntity
import com.beyondguo.imprints.data.ContentDraftEntity
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.EntryFtsEntity
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.EntryTagEntity
import com.beyondguo.imprints.data.FutureLetterEntity
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.MediaReferenceEntity
import com.beyondguo.imprints.data.PlaceEntity
import com.beyondguo.imprints.data.ReviewEntity
import com.beyondguo.imprints.data.ReviewInvitationStateEntity
import com.beyondguo.imprints.data.ReviewSourceEntity
import com.beyondguo.imprints.data.ReviewSourceMediaEntity
import com.beyondguo.imprints.data.SourceKind
import com.beyondguo.imprints.data.SourceVisibility
import com.beyondguo.imprints.data.TagCatalog
import com.beyondguo.imprints.data.TagEntity
import com.beyondguo.imprints.data.TagRules
import com.beyondguo.imprints.data.VisualMediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BackupPreview(val uuid: String, val hash: String, val entries: Int, val attachments: Int,
    val attachmentBytes: Long, val duplicates: Int, val expired: Int, val feelingMode: String,
    /** 新对象类型（格式 v2）；旧备份缺这些集合时为 0 */
    val letters: Int = 0, val reviews: Int = 0, val drafts: Int = 0)

private data class FileSource(val id: String, val file: File, val sha: String)
private data class TempFile(val file: File, val key: ByteArray)
private data class ParsedBackup(val uuid: String, val hash: String, val metadata: JSONObject, val temps: Map<String, TempFile>, val attachments: Int)

class BackupService(private val context: Context, private val database: ImprintDatabase, private val repository: ImprintRepository) {
    private val dao = database.dao()
    private val mailDao = database.mailReviewDao()
    private val security = repository.security
    private val random = SecureRandom()

    suspend fun cleanupInterruptedImports() = repository.exclusive {
        withContext(Dispatchers.IO) {
            File(context.noBackupFilesDir, "backup-import").listFiles()?.forEach { if (it.isFile) it.delete() }
        }
    }

    suspend fun export(uri: Uri, password: CharArray) = repository.exclusive {
        require(password.size >= 8) { "备份口令至少 8 位" }
        withContext(Dispatchers.IO) {
            val cutoff = EntryRules.recycleCutoff(System.currentTimeMillis())
            val entries = dao.allEntries().filter { it.deletedAtMs == null || it.deletedAtMs > cutoff }
            val ids = entries.map { it.id }.toSet()
            val visuals = dao.allVisuals().filter { it.entryId in ids }
            val audio = dao.allAudio().filter { it.entryId in ids }
            val places = dao.allPlaces().filter { place -> entries.any { it.placeId == place.id } }
            // 标签：随备份整体搬运，导入端按 id 合并
            val tags = dao.tagsOnce()
            val entryTags = dao.allEntryTagsOnce().filter { it.entryId in ids }
            val feelings = entries.mapNotNull { entry ->
                dao.feeling(entry.id)?.let { entry.id to (security.decodeFeeling(it) ?: "") }
            }.toMap()
            // ---- 新对象类型（格式 v2）：信件 / 总结 / 快照 / 草稿 / 邀请偏好 ----
            val letters = mailDao.allLetters().filter { it.entryId in ids }
            val reviews = mailDao.allReviews().filter { it.deletedAtMs == null || it.deletedAtMs > cutoff }
            val reviewIds = reviews.map { it.id }.toSet()
            val reviewSources = mailDao.allSources().filter { it.reviewId in reviewIds }
            val reviewMedia = mailDao.allSourceMedia().filter { it.reviewId in reviewIds }
            val drafts = mailDao.allDrafts()
            val invitations = mailDao.invitationStates()
            // 草稿附件：路径与校验值从草稿 payload 里的附件记录取（与 media_references 一致）
            val draftFilePaths = LinkedHashMap<String, String>()
            drafts.forEach { draft -> draftFiles(draft.payload).forEach { file -> draftFilePaths.putIfAbsent(file.path, file.sha) } }

            // ---- 附件清单：主图 / 动态附属视频 / 音频 / 快照与草稿引用的文件，同名文件只打包一次 ----
            val fileSources = LinkedHashMap<String, FileSource>()
            fun addSource(path: String, sha: String) {
                if (path in fileSources) return
                fileSources[path] = FileSource(path, repository.files.resolve(path), sha)
            }
            visuals.forEach { v ->
                addSource(v.relativePath, v.sha256)
                val motion = v.motionVideoPath
                if (motion != null) addSource(motion, v.motionVideoSha256 ?: error("动态照片附属视频缺少校验值：${v.id}"))
            }
            audio.forEach { addSource(it.relativePath, it.sha256) }
            reviewMedia.forEach { addSource(it.relativePath, it.sha256) }
            draftFilePaths.forEach { (path, sha) -> addSource(path, sha) }
            val sources = fileSources.values.toList()

            val metadata = JSONObject()
                .put("formatVersion", 2)
                .put("feelingMode", security.state().feelingMode)
                .put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } })
                .put("visuals", JSONArray().apply { visuals.forEach { put(visualJson(it)) } })
                .put("audio", JSONArray().apply { audio.forEach { put(audioJson(it)) } })
                .put("places", JSONArray().apply { places.forEach { put(placeJson(it)) } })
                .put("tags", JSONArray().apply { tags.forEach { put(tagJson(it)) } })
                .put("entryTags", JSONArray().apply { entryTags.forEach { put(entryTagJson(it)) } })
                .put("feelings", JSONObject().apply { feelings.forEach { (id, text) -> put(id, text) } })
                .put("letters", JSONArray().apply { letters.forEach { put(letterJson(it)) } })
                .put("reviews", JSONArray().apply { reviews.forEach { put(reviewJson(it)) } })
                .put("reviewSources", JSONArray().apply { reviewSources.forEach { put(reviewSourceJson(it)) } })
                .put("reviewSourceMedia", JSONArray().apply { reviewMedia.forEach { put(reviewSourceMediaJson(it)) } })
                .put("drafts", JSONArray().apply { drafts.forEach { put(draftJson(it)) } })
                .put("reviewInvitations", JSONArray().apply { invitations.forEach { put(invitationJson(it)) } })
            val bytes = metadata.toString().toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                val output = DataOutputStream(BufferedOutputStream(raw))
                val header = createHeader(output)
                val key = header.key(password)
                try {
                    val frames = EncryptedFrames(output, header, key)
                    val data = DataOutputStream(frames)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                    data.writeInt(sources.size)
                    sources.forEach { source ->
                        check(source.file.isFile) { "附件已丢失，无法导出" }
                        data.writeUTF(source.id)
                        data.writeLong(source.file.length())
                        val digest = MessageDigest.getInstance("SHA-256")
                        FileInputStream(source.file).use { input -> copyExact(input, data, source.file.length(), digest) }
                        check(hex(digest.digest()) == source.sha) { "附件校验失败：${source.id}" }
                    }
                    frames.finish()
                } finally { key.fill(0); output.flush() }
            } ?: error("无法创建备份文件")
        }
    }

    suspend fun preview(uri: Uri, password: CharArray): BackupPreview = withContext(Dispatchers.IO) {
        val parsed = parse(uri, password, keepFiles = false)
        val entries = parsed.metadata.getJSONArray("entries")
        val visuals = parsed.metadata.getJSONArray("visuals")
        val audio = parsed.metadata.getJSONArray("audio")
        val letters = parsed.metadata.optJSONArray("letters")
        val reviews = parsed.metadata.optJSONArray("reviews")
        val drafts = parsed.metadata.optJSONArray("drafts")
        val reviewMedia = parsed.metadata.optJSONArray("reviewSourceMedia")
        var duplicates = 0
        var expired = 0
        val included = mutableSetOf<String>()
        val cutoff = EntryRules.recycleCutoff(System.currentTimeMillis())
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val id = entry.getString("id")
            val duplicate = dao.entry(id) != null
            if (duplicate) duplicates++
            val deleted = entry.optLong("deletedAtMs", 0)
            val isExpired = deleted != 0L && deleted <= cutoff
            if (isExpired) expired++
            if (!duplicate && !isExpired) included += id
        }
        var attachmentBytes = 0L
        for (i in 0 until visuals.length()) {
            val item = visuals.getJSONObject(i)
            if (item.getString("entryId") in included) {
                attachmentBytes = Math.addExact(attachmentBytes, item.getLong("byteLength"))
                val motionLen = item.optLong("motionVideoByteLength", 0)
                if (motionLen > 0) attachmentBytes = Math.addExact(attachmentBytes, motionLen)
            }
        }
        for (i in 0 until audio.length()) {
            val item = audio.getJSONObject(i)
            if (item.getString("entryId") in included) attachmentBytes = Math.addExact(attachmentBytes, item.getLong("byteLength"))
        }
        // 快照与草稿引用的文件也占空间（按保守口径全量计入）
        if (reviewMedia != null) {
            for (i in 0 until reviewMedia.length()) {
                attachmentBytes = Math.addExact(attachmentBytes, reviewMedia.getJSONObject(i).getLong("byteLength"))
            }
        }
        if (drafts != null) {
            for (i in 0 until drafts.length()) {
                draftFiles(drafts.getJSONObject(i).optString("payload"))
                    .forEach { file -> attachmentBytes = Math.addExact(attachmentBytes, file.byteLength) }
            }
        }
        require(attachmentBytes >= 0) { "附件长度无效" }
        // 三类对象分别报告：不能把信件/总结都算成普通印迹
        val letterEntryIds = mutableSetOf<String>()
        if (letters != null) {
            for (i in 0 until letters.length()) letterEntryIds += letters.getJSONObject(i).getString("entryId")
        }
        val ordinaryEntries = (0 until entries.length()).count { entries.getJSONObject(it).getString("id") !in letterEntryIds }
        BackupPreview(parsed.uuid, parsed.hash, ordinaryEntries, parsed.attachments, attachmentBytes,
            duplicates, expired, parsed.metadata.getString("feelingMode"),
            letters = letterEntryIds.size, reviews = reviews?.length() ?: 0, drafts = drafts?.length() ?: 0)
    }

    suspend fun import(uri: Uri, password: CharArray, expected: BackupPreview, allowPlaintextConversion: Boolean): Int = repository.exclusive {
        withContext(Dispatchers.IO) {
            require(expected.attachmentBytes <= StatFs(context.filesDir.path).availableBytes / 2) {
                "本机可用空间不足，安全导入约需附件大小的两倍空间"
            }
            val parsed = parse(uri, password, keepFiles = true)
            val newFiles = mutableListOf<File>()
            try {
                check(parsed.uuid == expected.uuid && parsed.hash == expected.hash) { "备份文件在预览后发生变化" }
                val sourceMode = parsed.metadata.getString("feelingMode")
                val currentMode = security.state().feelingMode
                check(sourceMode == "ON" || sourceMode == "OFF") { "备份感受模式无效" }
                check(sourceMode != "ON" || currentMode != "OFF" || allowPlaintextConversion) {
                    "需确认导入后感受将明文存储"
                }
                if (currentMode == "ON") check(security.isFeelingUnlocked()) { "请先解锁本机感受" }
                val entriesJson = parsed.metadata.getJSONArray("entries")
                val visualsJson = parsed.metadata.getJSONArray("visuals")
                val audioJson = parsed.metadata.getJSONArray("audio")
                val placesJson = parsed.metadata.getJSONArray("places")
                // 旧备份没有 tags / entryTags 两个数组，用 optJSONArray 兜底为“无标签”
                val tagsJson = parsed.metadata.optJSONArray("tags")
                val entryTagsJson = parsed.metadata.optJSONArray("entryTags")
                val feelingsJson = parsed.metadata.getJSONObject("feelings")
                val cutoff = EntryRules.recycleCutoff(System.currentTimeMillis())
                val entries = (0 until entriesJson.length()).map { entriesJson.getJSONObject(it) }
                    .filter { raw ->
                        dao.entry(raw.getString("id")) == null &&
                            (raw.optLong("deletedAtMs", 0) == 0L || raw.getLong("deletedAtMs") > cutoff)
                    }
                val included = entries.map { it.getString("id") }.toSet()
                val includedPlaceIds = entries.mapNotNull { entry ->
                    entry.optString("placeId").takeIf { it.isNotEmpty() }
                }.toSet()
                val placeMap = mutableMapOf<String, PlaceEntity>()
                for (i in 0 until placesJson.length()) {
                    val raw = placesJson.getJSONObject(i)
                    val originalId = raw.getString("id")
                    if (originalId in includedPlaceIds) {
                        placeMap[originalId] = placeFromJson(raw).copy(id = UUID.randomUUID().toString())
                    }
                }
                // 标签按 id 合并（id 即语义，跨设备一致）。本机已有的 id 不覆盖，
                // 所以先取本库标签，既用于去重也用于校验 entryTags 的引用完整性。
                val knownTagIds = dao.tagsOnce().map { it.id }.toSet()
                val tags = buildList {
                    if (tagsJson != null) {
                        for (i in 0 until tagsJson.length()) {
                            val tag = tagFromJson(tagsJson.getJSONObject(i))
                            // 本机已有同名 id 的行保持不动；新 id 至少要有个非空标签名
                            if (tag.id !in knownTagIds && TagRules.normalizeName(tag.name).isNotEmpty()) {
                                add(tag.copy(name = fitTagName(tag.name)))
                            }
                        }
                    }
                }.distinctBy { it.id }
                val importableTagIds = knownTagIds + tags.map { it.id }
                val entryTags = buildList {
                    if (entryTagsJson != null) {
                        for (i in 0 until entryTagsJson.length()) {
                            val raw = entryTagsJson.getJSONObject(i)
                            val entryId = raw.getString("entryId")
                            val tagId = raw.getString("tagId")
                            if (entryId in included && tagId in importableTagIds) add(EntryTagEntity(entryId, tagId))
                        }
                    }
                }.distinctBy { it.entryId to it.tagId }
                val fileMap = mutableMapOf<String, String>()
                fun install(oldId: String): String {
                    fileMap[oldId]?.let { return it }
                    val temp = parsed.temps[oldId] ?: error("附件缺失：$oldId")
                    val newId = UUID.randomUUID().toString()
                    val destination = repository.files.resolve(newId)
                    newFiles += destination
                    decryptTemp(temp, destination)
                    fileMap[oldId] = newId
                    return newId
                }
                val visuals = (0 until visualsJson.length()).map { visualsJson.getJSONObject(it) }
                    .filter { it.getString("entryId") in included }
                    .map { raw ->
                        val path = install(raw.getString("id"))
                        val motionOld = raw.optString("motionVideoPath").takeIf { it.isNotEmpty() }
                        val motionNew = motionOld?.let { install(it) }
                        visualFromJson(raw).copy(
                            id = path,
                            relativePath = path,
                            motionVideoPath = motionNew,
                            motionVideoSha256 = motionNew?.let { raw.optString("motionVideoSha256").takeIf { s -> s.isNotEmpty() } },
                            motionVideoByteLength = motionNew?.let { raw.optLong("motionVideoByteLength").takeIf { n -> n > 0 } },
                        )
                    }
                val audio = (0 until audioJson.length()).map { audioJson.getJSONObject(it) }
                    .filter { it.getString("entryId") in included }
                    .map { raw ->
                        val path = install(raw.getString("id"))
                        audioFromJson(raw).copy(id = path, relativePath = path)
                    }
                val feelings = entries.mapNotNull { raw ->
                    val id = raw.getString("id")
                    if (feelingsJson.has(id)) security.encodeFeeling(id, feelingsJson.getString(id)) else null
                }
                // ---- 信件 / 总结 / 快照 / 草稿 / 邀请（格式 v2；旧备份缺集合按空处理） ----
                val lettersJson = parsed.metadata.optJSONArray("letters")
                val reviewsJson = parsed.metadata.optJSONArray("reviews")
                val reviewSourcesJson = parsed.metadata.optJSONArray("reviewSources")
                val reviewMediaJson = parsed.metadata.optJSONArray("reviewSourceMedia")
                val draftsJson = parsed.metadata.optJSONArray("drafts")
                val invitationsJson = parsed.metadata.optJSONArray("reviewInvitations")

                val backupLetters = mutableMapOf<String, FutureLetterEntity>()
                if (lettersJson != null) {
                    for (i in 0 until lettersJson.length()) {
                        val letter = letterFromJson(lettersJson.getJSONObject(i))
                        backupLetters[letter.entryId] = letter
                    }
                }
                // 信件行只随本次成功导入的 entries 落库；重复 ID 不覆盖本机信件状态
                val letters = backupLetters.values.filter { it.entryId in included && dao.entry(it.entryId) == null }

                val reviews = mutableListOf<ReviewEntity>()
                val reviewSourceRows = mutableListOf<ReviewSourceEntity>()
                val reviewMediaRows = mutableListOf<ReviewSourceMediaEntity>()
                if (reviewsJson != null) {
                    for (i in 0 until reviewsJson.length()) {
                        val review = reviewFromJson(reviewsJson.getJSONObject(i))
                        if (mailDao.review(review.id) != null) continue
                        val deleted = review.deletedAtMs
                        if (deleted != null && deleted <= cutoff) continue
                        reviews += review
                    }
                }
                val importableReviewIds = reviews.map { it.id }.toSet()
                if (reviewSourcesJson != null) {
                    for (i in 0 until reviewSourcesJson.length()) {
                        val source = reviewSourceFromJson(reviewSourcesJson.getJSONObject(i))
                        if (source.reviewId !in importableReviewIds) continue
                        val restorable = sourceRestorable(source.sourceKind, source.sourceId, included, backupLetters)
                        reviewSourceRows += if (restorable) {
                            source
                        } else {
                            // 缺失/已删/未读来源：保留无内容占位，不恢复敏感快照
                            source.copy(
                                visibility = if (dao.entry(source.sourceId)?.deletedAtMs != null) SourceVisibility.RECYCLED.name else SourceVisibility.PURGED.name,
                                titleSnapshot = "", bodySnapshot = "", tagSnapshot = "", contentHash = "",
                            )
                        }
                    }
                }
                if (reviewMediaJson != null) {
                    for (i in 0 until reviewMediaJson.length()) {
                        val media = reviewSourceMediaFromJson(reviewMediaJson.getJSONObject(i))
                        if (media.reviewId !in importableReviewIds) continue
                        if (!sourceRestorable(media.sourceKind, media.sourceId, included, backupLetters)) continue
                        val path = install(media.relativePath)
                        reviewMediaRows += media.copy(relativePath = path)
                    }
                }
                val draftRows = mutableListOf<ContentDraftEntity>()
                if (draftsJson != null) {
                    for (i in 0 until draftsJson.length()) {
                        val draft = draftFromJson(draftsJson.getJSONObject(i))
                        if (mailDao.draft(draft.id) != null) continue
                        // 先落盘草稿附件，再把 payload 里的旧路径重写成新文件 id
                        draftFiles(draft.payload).forEach { file -> install(file.path) }
                        draftRows += draft.copy(payload = remapDraftPayload(draft.payload, fileMap))
                    }
                }
                val invitationRows = mutableListOf<ReviewInvitationStateEntity>()
                if (invitationsJson != null) {
                    for (i in 0 until invitationsJson.length()) {
                        invitationRows += invitationFromJson(invitationsJson.getJSONObject(i))
                    }
                }
                val importedLetterIds = letters.map { it.entryId }.toSet()
                database.withTransaction {
                    placeMap.values.forEach { dao.upsertPlace(it) }
                    entries.forEach { raw ->
                        val entry = entryFromJson(raw)
                        val placeId = entry.placeId?.let { placeMap[it]?.id }
                        dao.upsertEntry(entry.copy(placeId = placeId))
                        // 信件不进正文 FTS：搜索只查普通印迹
                        if (entry.body.isNotBlank() && entry.id !in importedLetterIds) {
                            dao.insertFts(EntryFtsEntity(entryId = entry.id, body = entry.body))
                        }
                    }
                    if (visuals.isNotEmpty()) dao.insertVisuals(visuals)
                    audio.forEach { dao.upsertAudio(it) }
                    feelings.forEach { dao.upsertFeeling(it) }
                    // IGNORE：导出的标签行不得覆盖本机既有预置标签
                    if (tags.isNotEmpty()) dao.insertTags(tags)
                    if (entryTags.isNotEmpty()) dao.insertEntryTags(entryTags)
                    // 信件 / 总结 / 快照 / 草稿 / 邀请
                    letters.forEach { mailDao.upsertLetter(it) }
                    reviews.forEach { mailDao.upsertReview(it) }
                    if (reviewSourceRows.isNotEmpty()) mailDao.insertSources(reviewSourceRows)
                    if (reviewMediaRows.isNotEmpty()) mailDao.insertSourceMedia(reviewMediaRows)
                    draftRows.forEach { mailDao.upsertDraft(it) }
                    invitationRows.forEach { mailDao.upsertInvitationState(it) }
                    // 媒体所有权重建：普通印迹/信件、总结快照、草稿（替换附件时据此保护共享文件）
                    included.forEach { entryId ->
                        val paths = (visuals.filter { it.entryId == entryId }.flatMap { listOfNotNull(it.relativePath, it.motionVideoPath) } +
                            audio.filter { it.entryId == entryId }.map { it.relativePath }).toSet()
                        if (paths.isNotEmpty()) {
                            val ownerKind = if (entryId in importedLetterIds) "LETTER" else "ENTRY"
                            mailDao.insertMediaRefs(paths.map { MediaReferenceEntity(ownerKind, entryId, it) })
                        }
                    }
                    reviewMediaRows.groupBy { it.reviewId }.forEach { (reviewId, rows) ->
                        mailDao.insertMediaRefs(rows.map { MediaReferenceEntity("REVIEW", reviewId, it.relativePath) })
                    }
                    draftRows.forEach { draft ->
                        val paths = draftFiles(draft.payload).map { it.path }
                        if (paths.isNotEmpty()) {
                            mailDao.insertMediaRefs(paths.map { MediaReferenceEntity("DRAFT", draft.id, it) })
                        }
                    }
                }
                entries.size
            } catch (error: Exception) {
                newFiles.forEach { it.delete() }
                throw error
            } finally { parsed.temps.values.forEach { it.file.delete(); it.key.fill(0) } }
        }
    }

    private suspend fun parse(uri: Uri, password: CharArray, keepFiles: Boolean): ParsedBackup {
        val temps = mutableMapOf<String, TempFile>()
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val input = DataInputStream(BufferedInputStream(raw))
                val header = readHeader(input)
                val key = header.key(password)
                try {
                    val frames = DecryptedFrames(input, header, key)
                    val data = DataInputStream(frames)
                    val size = data.readInt()
                    require(size in 2..(64 * 1024 * 1024)) { "备份元数据长度错误" }
                    val metadata = JSONObject(String(ByteArray(size).also { data.readFully(it) }, Charsets.UTF_8))
                    val expectedIds = mutableMapOf<String, Pair<String, Long>>()
                    val visuals = metadata.getJSONArray("visuals")
                    val audio = metadata.getJSONArray("audio")
                    // 同一个文件可能同时被 entries 与快照/草稿引用：清单按文件去重，重复项校验一致即可
                    fun expect(id: String, sha: String, length: Long) {
                        val previous = expectedIds.putIfAbsent(id, sha to length)
                        check(previous == null || previous == (sha to length)) { "附件清单不一致" }
                    }
                    val cutoff = EntryRules.recycleCutoff(System.currentTimeMillis())
                    val retainedEntryIds: Set<String>
                    val retainedReviewIds: Set<String>
                    val retainedDraftIds: Set<String>
                    if (keepFiles) {
                        val entries = metadata.getJSONArray("entries")
                        retainedEntryIds = buildSet {
                            for (i in 0 until entries.length()) {
                                val entry = entries.getJSONObject(i)
                                val id = entry.getString("id")
                                val deleted = entry.optLong("deletedAtMs", 0)
                                if (dao.entry(id) == null && (deleted == 0L || deleted > cutoff)) add(id)
                            }
                        }
                        val reviews = metadata.optJSONArray("reviews")
                        retainedReviewIds = buildSet {
                            if (reviews != null) for (i in 0 until reviews.length()) {
                                val review = reviews.getJSONObject(i)
                                val id = review.getString("id")
                                val deleted = review.optLong("deletedAtMs", 0)
                                if (mailDao.review(id) == null && (deleted == 0L || deleted > cutoff)) add(id)
                            }
                        }
                        val drafts = metadata.optJSONArray("drafts")
                        retainedDraftIds = buildSet {
                            if (drafts != null) for (i in 0 until drafts.length()) {
                                val id = drafts.getJSONObject(i).getString("id")
                                if (mailDao.draft(id) == null) add(id)
                            }
                        }
                    } else {
                        retainedEntryIds = emptySet()
                        retainedReviewIds = emptySet()
                        retainedDraftIds = emptySet()
                    }
                    // 备份里的信件集合：用于判断快照来源是否可恢复内容
                    val backupLetters = mutableMapOf<String, FutureLetterEntity>()
                    metadata.optJSONArray("letters")?.let { letters ->
                        for (i in 0 until letters.length()) {
                            val letter = letterFromJson(letters.getJSONObject(i))
                            backupLetters[letter.entryId] = letter
                        }
                    }
                    val retainedFileIds = mutableSetOf<String>()
                    for (i in 0 until visuals.length()) {
                        val item = visuals.getJSONObject(i)
                        expect(item.getString("id"), item.getString("sha256"), item.getLong("byteLength"))
                        if (item.getString("entryId") in retainedEntryIds) retainedFileIds += item.getString("id")
                        val motionId = item.optString("motionVideoPath").takeIf { it.isNotEmpty() }
                        if (motionId != null) {
                            val motionSha = item.optString("motionVideoSha256")
                            val motionLen = item.optLong("motionVideoByteLength", 0)
                            check(motionSha.isNotEmpty() && motionLen > 0) { "动态照片附属视频元数据无效" }
                            expect(motionId, motionSha, motionLen)
                            if (item.getString("entryId") in retainedEntryIds) retainedFileIds += motionId
                        }
                    }
                    for (i in 0 until audio.length()) {
                        val item = audio.getJSONObject(i)
                        expect(item.getString("id"), item.getString("sha256"), item.getLong("byteLength"))
                        if (item.getString("entryId") in retainedEntryIds) retainedFileIds += item.getString("id")
                    }
                    // 快照媒体：只有来源仍可见（未删、信件已读）的快照才恢复文件
                    metadata.optJSONArray("reviewSourceMedia")?.let { media ->
                        for (i in 0 until media.length()) {
                            val item = media.getJSONObject(i)
                            val path = item.getString("relativePath")
                            expect(path, item.optString("sha256"), item.getLong("byteLength"))
                            if (item.getString("reviewId") in retainedReviewIds &&
                                sourceRestorable(item.getString("sourceKind"), item.getString("sourceId"), retainedEntryIds, backupLetters)
                            ) {
                                retainedFileIds += path
                            }
                        }
                    }
                    // 草稿附件
                    metadata.optJSONArray("drafts")?.let { drafts ->
                        for (i in 0 until drafts.length()) {
                            val draft = drafts.getJSONObject(i)
                            val keep = draft.getString("id") in retainedDraftIds
                            draftFiles(draft.optString("payload")).forEach { file ->
                                expect(file.path, file.sha, file.byteLength)
                                if (keep) retainedFileIds += file.path
                            }
                        }
                    }
                    val count = data.readInt()
                    require(count == expectedIds.size && count in 0..1_000_000) { "附件清单不一致" }
                    val seen = mutableSetOf<String>()
                    repeat(count) {
                        val id = data.readUTF()
                        val length = data.readLong()
                        require(id in expectedIds && length >= 0 && length == expectedIds[id]?.second && seen.add(id)) { "附件记录无效" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        if (keepFiles && id in retainedFileIds) {
                            val temp = createEncryptedTemp()
                            temps[id] = temp
                            encryptToTemp(data, length, temp, digest)
                        } else copyExact(data, DISCARD, length, digest)
                        require(hex(digest.digest()) == expectedIds[id]?.first) { "附件校验失败" }
                    }
                    require(data.read() == -1) { "备份数据尾部无效" }
                    return ParsedBackup(header.uuid, hex(frames.verifiedHash ?: error("备份认证不完整")), metadata, temps, count)
                } finally { key.fill(0) }
            } ?: error("无法读取备份文件")
        } catch (error: Exception) {
            temps.values.forEach { it.file.delete(); it.key.fill(0) }
            throw error
        }
    }

    private fun createEncryptedTemp(): TempFile {
        val dir = File(context.noBackupFilesDir, "backup-import").also { it.mkdirs() }
        return TempFile(File(dir, UUID.randomUUID().toString()), ByteArray(32).also(random::nextBytes))
    }

    private fun encryptToTemp(input: InputStream, length: Long, temp: TempFile, digest: MessageDigest) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(temp.key, "AES"))
        FileOutputStream(temp.file).use { raw ->
            raw.write(cipher.iv)
            CipherOutputStream(raw, cipher).use { encrypted -> copyExact(input, encrypted, length, digest) }
        }
    }

    private fun decryptTemp(temp: TempFile, destination: File) {
        FileInputStream(temp.file).use { input ->
            val iv = ByteArray(12).also { DataInputStream(input).readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(temp.key, "AES"), GCMParameterSpec(128, iv))
            CipherInputStream(input, cipher).use { decrypted ->
                FileOutputStream(destination).use { output -> decrypted.copyTo(output); output.fd.sync() }
            }
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, length: Long, digest: MessageDigest) {
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "备份提前结束" }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            remaining -= count
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun entryJson(e: EntryEntity) = JSONObject().put("id", e.id).put("body", e.body)
        .put("eventLocalMinute", e.eventLocalMinute).put("eventZoneId", e.eventZoneId)
        .put("createdAtMs", e.createdAtMs).put("updatedAtMs", e.updatedAtMs)
        .put("deletedAtMs", e.deletedAtMs ?: 0).put("placeId", e.placeId ?: "")
    private fun entryFromJson(j: JSONObject) = EntryEntity(j.getString("id"), j.getString("body"), j.getString("eventLocalMinute"),
        j.getString("eventZoneId"), j.getLong("createdAtMs"), j.getLong("updatedAtMs"),
        j.getLong("deletedAtMs").takeIf { it != 0L }, j.getString("placeId").takeIf { it.isNotEmpty() })
    private fun visualJson(v: VisualMediaEntity) = JSONObject().put("id", v.id).put("entryId", v.entryId)
        .put("kind", v.kind).put("ordinal", v.ordinal).put("mime", v.mime)
        .put("byteLength", v.byteLength).put("sha256", v.sha256).put("createdAtMs", v.createdAtMs)
        .put("motionVideoPath", v.motionVideoPath ?: "")
        .put("motionVideoSha256", v.motionVideoSha256 ?: "")
        .put("motionVideoByteLength", v.motionVideoByteLength ?: 0L)
    private fun visualFromJson(j: JSONObject) = VisualMediaEntity(j.getString("id"), j.getString("entryId"), j.getString("kind"),
        j.getInt("ordinal"), "", j.getString("mime"), j.getLong("byteLength"), j.getString("sha256"), j.getLong("createdAtMs"),
        motionVideoPath = j.optString("motionVideoPath").takeIf { it.isNotEmpty() },
        motionVideoSha256 = j.optString("motionVideoSha256").takeIf { it.isNotEmpty() },
        motionVideoByteLength = j.optLong("motionVideoByteLength").takeIf { it > 0 })
    private fun audioJson(a: AudioEntity) = JSONObject().put("id", a.id).put("entryId", a.entryId)
        .put("mime", a.mime).put("byteLength", a.byteLength).put("sha256", a.sha256)
        .put("durationMs", a.durationMs ?: 0)
    private fun audioFromJson(j: JSONObject) = AudioEntity(j.getString("entryId"), j.getString("id"), "", j.getString("mime"),
        j.getLong("byteLength"), j.getString("sha256"), j.getLong("durationMs").takeIf { it != 0L })
    private fun placeJson(p: PlaceEntity) = JSONObject().put("id", p.id).put("name", p.name).put("address", p.address)
        .put("latitude", p.latitude ?: JSONObject.NULL).put("longitude", p.longitude ?: JSONObject.NULL)
        .put("coordinateSystem", p.coordinateSystem ?: "").put("provider", p.provider ?: "")
        .put("providerPoiId", p.providerPoiId ?: "")
    private fun placeFromJson(j: JSONObject) = PlaceEntity(j.getString("id"), j.getString("name"), j.getString("address"),
        if (j.isNull("latitude")) null else j.getDouble("latitude"), if (j.isNull("longitude")) null else j.getDouble("longitude"),
        j.getString("coordinateSystem").takeIf { it.isNotEmpty() }, j.getString("provider").takeIf { it.isNotEmpty() },
        j.getString("providerPoiId").takeIf { it.isNotEmpty() })
    private fun tagJson(t: TagEntity) = JSONObject().put("id", t.id).put("name", t.name)
        .put("ordinal", t.ordinal).put("archived", t.archived)
        .put("preset", t.preset).put("createdAtMs", t.createdAtMs)
    private fun tagFromJson(j: JSONObject): TagEntity {
        val id = j.getString("id")
        return TagEntity(
            id = id,
            name = j.getString("name"),
            ordinal = j.optInt("ordinal", 0),
            archived = j.optBoolean("archived", false),
            // v1.4 及更早的备份没有 preset 字段：按目录判断，避免用户标签被当成预置而不可删
            preset = if (j.has("preset")) j.getBoolean("preset") else TagCatalog.isPreset(id),
            createdAtMs = j.optLong("createdAtMs", 0),
        )
    }

    /** 外部备份里的标签名可能超出当前上限；截断而不是丢弃，否则印迹会白丢一个标签关联。 */
    private fun fitTagName(raw: String): String {
        val normalized = TagRules.normalizeName(raw)
        if (TagRules.isValidName(normalized)) return normalized
        val end = normalized.offsetByCodePoints(0, TagRules.MAX_NAME_LENGTH)
        return normalized.substring(0, end)
    }
    private fun entryTagJson(e: EntryTagEntity) = JSONObject().put("entryId", e.entryId).put("tagId", e.tagId)

    // ---------- 格式 v2：信件 / 总结 / 快照 / 草稿 / 邀请 ----------

    private data class DraftFile(val path: String, val sha: String, val byteLength: Long)

    /**
     * 快照内容是否可恢复（SYS-04 / RV-06）：
     * 来源必须存在且可见（未删）；信件还必须**到期且已确认阅读**。
     * 不能借导入的快照把本机已删除或未读信的内容重新显示出来。
     */
    private suspend fun sourceRestorable(
        sourceKind: String,
        sourceId: String,
        importedEntryIds: Set<String>,
        backupLetters: Map<String, FutureLetterEntity>,
    ): Boolean = when (sourceKind) {
        SourceKind.LETTER.name -> {
            val letter = backupLetters[sourceId]?.takeIf { it.entryId in importedEntryIds } ?: mailDao.letter(sourceId)
            letter != null && letter.deliveryReadAtMs != null &&
                (letter.deliveredAtMs != null || System.currentTimeMillis() >= letter.deliverAtMs)
        }
        else -> {
            val entry = dao.entry(sourceId)
            sourceId in importedEntryIds || (entry != null && entry.deletedAtMs == null)
        }
    }

    /** 草稿 payload 引用的附件（信件草稿的图片/视频/音频；回顾草稿无附件）。 */    private fun draftFiles(payloadJson: String): List<DraftFile> {
        if (payloadJson.isBlank()) return emptyList()
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<DraftFile>()
        payload.optJSONArray("visuals")?.let { array ->
            (0 until array.length()).forEach { index ->
                val item = array.getJSONObject(index)
                item.optString("path").takeIf { it.isNotBlank() }?.let { path ->
                    result += DraftFile(path, item.optString("sha256"), item.optLong("byteLength"))
                }
                item.optString("motionPath").takeIf { it.isNotBlank() }?.let { motion ->
                    result += DraftFile(motion, item.optString("motionSha256"), item.optLong("motionByteLength"))
                }
            }
        }
        (payload.opt("audio") as? JSONObject)?.let { item ->
            item.optString("path").takeIf { it.isNotBlank() }?.let { path ->
                result += DraftFile(path, item.optString("sha256"), item.optLong("byteLength"))
            }
        }
        return result
    }

    /** 草稿 payload 里的旧文件路径 → 新文件 id（导入时保持引用关系）。 */
    private fun remapDraftPayload(payloadJson: String, fileMap: Map<String, String>): String {
        if (payloadJson.isBlank()) return payloadJson
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return payloadJson
        fun remap(item: JSONObject) {
            item.optString("path").takeIf { it.isNotBlank() }?.let { item.put("path", fileMap[it] ?: it) }
            item.optString("motionPath").takeIf { it.isNotBlank() }?.let { item.put("motionPath", fileMap[it] ?: it) }
        }
        payload.optJSONArray("visuals")?.let { array -> (0 until array.length()).forEach { remap(array.getJSONObject(it)) } }
        (payload.opt("audio") as? JSONObject)?.let(::remap)
        return payload.toString()
    }

    private fun letterJson(l: FutureLetterEntity) = JSONObject()
        .put("entryId", l.entryId).put("title", l.title)
        .put("deliverAtMs", l.deliverAtMs).put("deliverLocalMinute", l.deliverLocalMinute).put("deliverZoneId", l.deliverZoneId)
        .put("deliveredAtMs", l.deliveredAtMs ?: 0).put("firstOpenedAtMs", l.firstOpenedAtMs ?: 0)
        .put("deliveryReadAtMs", l.deliveryReadAtMs ?: 0)
        .put("notifyEnabled", l.notifyEnabled).put("scheduleRevision", l.scheduleRevision)

    private fun letterFromJson(j: JSONObject) = FutureLetterEntity(
        entryId = j.getString("entryId"),
        title = j.optString("title"),
        deliverAtMs = j.getLong("deliverAtMs"),
        deliverLocalMinute = j.getString("deliverLocalMinute"),
        deliverZoneId = j.getString("deliverZoneId"),
        deliveredAtMs = j.optLong("deliveredAtMs").takeIf { it != 0L },
        firstOpenedAtMs = j.optLong("firstOpenedAtMs").takeIf { it != 0L },
        deliveryReadAtMs = j.optLong("deliveryReadAtMs").takeIf { it != 0L },
        notifyEnabled = j.optBoolean("notifyEnabled", true),
        scheduleRevision = j.optInt("scheduleRevision", 1),
    )

    private fun reviewJson(r: ReviewEntity) = JSONObject()
        .put("id", r.id).put("periodKind", r.periodKind)
        .put("startLocalDate", r.startLocalDate).put("endLocalDateInclusive", r.endLocalDateInclusive)
        .put("title", r.title).put("userBody", r.userBody).put("closingText", r.closingText)
        .put("coverSourceKey", r.coverSourceKey ?: "")
        .put("selectedTagIds", r.selectedTagIds).put("tagNameSnapshot", r.tagNameSnapshot)
        .put("createdAtMs", r.createdAtMs).put("updatedAtMs", r.updatedAtMs)
        .put("deletedAtMs", r.deletedAtMs ?: 0).put("snapshotRevision", r.snapshotRevision)

    private fun reviewFromJson(j: JSONObject) = ReviewEntity(
        id = j.getString("id"),
        periodKind = j.getString("periodKind"),
        startLocalDate = j.getString("startLocalDate"),
        endLocalDateInclusive = j.getString("endLocalDateInclusive"),
        title = j.optString("title"),
        userBody = j.optString("userBody"),
        closingText = j.optString("closingText"),
        coverSourceKey = j.optString("coverSourceKey").takeIf { it.isNotBlank() },
        selectedTagIds = j.optString("selectedTagIds"),
        tagNameSnapshot = j.optString("tagNameSnapshot"),
        createdAtMs = j.getLong("createdAtMs"),
        updatedAtMs = j.getLong("updatedAtMs"),
        deletedAtMs = j.optLong("deletedAtMs").takeIf { it != 0L },
        snapshotRevision = j.optInt("snapshotRevision", 1),
    )

    private fun reviewSourceJson(s: ReviewSourceEntity) = JSONObject()
        .put("reviewId", s.reviewId).put("sourceKind", s.sourceKind).put("sourceId", s.sourceId)
        .put("sourceUpdatedAtMs", s.sourceUpdatedAtMs).put("contentHash", s.contentHash)
        .put("eventDate", s.eventDate).put("included", s.included).put("featured", s.featured)
        .put("featuredOrder", s.featuredOrder).put("titleSnapshot", s.titleSnapshot)
        .put("bodySnapshot", s.bodySnapshot).put("tagSnapshot", s.tagSnapshot)
        .put("visibility", s.visibility).put("createdAtMs", s.createdAtMs)

    private fun reviewSourceFromJson(j: JSONObject) = ReviewSourceEntity(
        reviewId = j.getString("reviewId"),
        sourceKind = j.getString("sourceKind"),
        sourceId = j.getString("sourceId"),
        sourceUpdatedAtMs = j.optLong("sourceUpdatedAtMs"),
        contentHash = j.optString("contentHash"),
        eventDate = j.getString("eventDate"),
        included = j.optBoolean("included", true),
        featured = j.optBoolean("featured", false),
        featuredOrder = j.optInt("featuredOrder", 0),
        titleSnapshot = j.optString("titleSnapshot"),
        bodySnapshot = j.optString("bodySnapshot"),
        tagSnapshot = j.optString("tagSnapshot"),
        visibility = j.optString("visibility").ifBlank { "VISIBLE" },
        createdAtMs = j.optLong("createdAtMs"),
    )

    private fun reviewSourceMediaJson(m: ReviewSourceMediaEntity) = JSONObject()
        .put("id", m.id).put("reviewId", m.reviewId).put("sourceKind", m.sourceKind).put("sourceId", m.sourceId)
        .put("role", m.role).put("ordinal", m.ordinal).put("relativePath", m.relativePath)
        .put("kind", m.kind).put("mime", m.mime).put("byteLength", m.byteLength).put("sha256", m.sha256)

    private fun reviewSourceMediaFromJson(j: JSONObject) = ReviewSourceMediaEntity(
        id = j.getString("id"),
        reviewId = j.getString("reviewId"),
        sourceKind = j.getString("sourceKind"),
        sourceId = j.getString("sourceId"),
        role = j.getString("role"),
        ordinal = j.optInt("ordinal"),
        relativePath = j.getString("relativePath"),
        kind = j.getString("kind"),
        mime = j.optString("mime"),
        byteLength = j.optLong("byteLength"),
        sha256 = j.optString("sha256"),
    )

    private fun draftJson(d: ContentDraftEntity) = JSONObject()
        .put("id", d.id).put("type", d.type).put("targetId", d.targetId ?: "")
        .put("payload", d.payload).put("updatedAtMs", d.updatedAtMs)

    private fun draftFromJson(j: JSONObject) = ContentDraftEntity(
        id = j.getString("id"),
        type = j.getString("type"),
        targetId = j.optString("targetId").takeIf { it.isNotBlank() },
        payload = j.optString("payload"),
        updatedAtMs = j.optLong("updatedAtMs"),
    )

    private fun invitationJson(i: ReviewInvitationStateEntity) = JSONObject()
        .put("periodKind", i.periodKind).put("startLocalDate", i.startLocalDate)
        .put("endLocalDateInclusive", i.endLocalDateInclusive).put("closedAtMs", i.closedAtMs)

    private fun invitationFromJson(j: JSONObject) = ReviewInvitationStateEntity(
        periodKind = j.getString("periodKind"),
        startLocalDate = j.getString("startLocalDate"),
        endLocalDateInclusive = j.getString("endLocalDateInclusive"),
        closedAtMs = j.optLong("closedAtMs"),
    )

    companion object {
        private val DISCARD = object : OutputStream() { override fun write(b: Int) = Unit }
    }
}
