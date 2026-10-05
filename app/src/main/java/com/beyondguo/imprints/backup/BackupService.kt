package com.beyondguo.imprints.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.beyondguo.imprints.data.AudioEntity
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.EntryFtsEntity
import com.beyondguo.imprints.data.EntryRules
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.PlaceEntity
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
    val attachmentBytes: Long, val duplicates: Int, val expired: Int, val feelingMode: String)

private data class FileSource(val id: String, val file: File, val sha: String)
private data class TempFile(val file: File, val key: ByteArray)
private data class ParsedBackup(val uuid: String, val hash: String, val metadata: JSONObject, val temps: Map<String, TempFile>, val attachments: Int)

class BackupService(private val context: Context, private val database: ImprintDatabase, private val repository: ImprintRepository) {
    private val dao = database.dao()
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
            val feelings = entries.mapNotNull { entry ->
                dao.feeling(entry.id)?.let { entry.id to (security.decodeFeeling(it) ?: "") }
            }.toMap()
            // 主图 + 可选动态照片附属视频
            val sources = visuals.flatMap { v ->
                val main = FileSource(v.id, repository.files.resolve(v.relativePath), v.sha256)
                val motion = v.motionVideoPath?.let { path ->
                    val sha = v.motionVideoSha256
                        ?: error("动态照片附属视频缺少校验值：${v.id}")
                    FileSource(path, repository.files.resolve(path), sha)
                }
                listOfNotNull(main, motion)
            } + audio.map { FileSource(it.id, repository.files.resolve(it.relativePath), it.sha256) }
            val metadata = JSONObject()
                .put("feelingMode", security.state().feelingMode)
                .put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } })
                .put("visuals", JSONArray().apply { visuals.forEach { put(visualJson(it)) } })
                .put("audio", JSONArray().apply { audio.forEach { put(audioJson(it)) } })
                .put("places", JSONArray().apply { places.forEach { put(placeJson(it)) } })
                .put("feelings", JSONObject().apply { feelings.forEach { (id, text) -> put(id, text) } })
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
        require(attachmentBytes >= 0) { "附件长度无效" }
        BackupPreview(parsed.uuid, parsed.hash, entries.length(), parsed.attachments, attachmentBytes,
            duplicates, expired, parsed.metadata.getString("feelingMode"))
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
                database.withTransaction {
                    placeMap.values.forEach { dao.upsertPlace(it) }
                    entries.forEach { raw ->
                        val entry = entryFromJson(raw)
                        val placeId = entry.placeId?.let { placeMap[it]?.id }
                        dao.upsertEntry(entry.copy(placeId = placeId))
                        if (entry.body.isNotBlank()) dao.insertFts(EntryFtsEntity(entryId = entry.id, body = entry.body))
                    }
                    if (visuals.isNotEmpty()) dao.insertVisuals(visuals)
                    audio.forEach { dao.upsertAudio(it) }
                    feelings.forEach { dao.upsertFeeling(it) }
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
                    val retainedEntryIds = if (keepFiles) {
                        val entries = metadata.getJSONArray("entries")
                        val cutoff = EntryRules.recycleCutoff(System.currentTimeMillis())
                        buildSet {
                            for (i in 0 until entries.length()) {
                                val entry = entries.getJSONObject(i)
                                val id = entry.getString("id")
                                val deleted = entry.optLong("deletedAtMs", 0)
                                if (dao.entry(id) == null && (deleted == 0L || deleted > cutoff)) add(id)
                            }
                        }
                    } else emptySet()
                    val retainedFileIds = mutableSetOf<String>()
                    for (i in 0 until visuals.length()) {
                        val item = visuals.getJSONObject(i)
                        check(expectedIds.put(item.getString("id"), item.getString("sha256") to item.getLong("byteLength")) == null) { "附件 ID 重复" }
                        if (item.getString("entryId") in retainedEntryIds) retainedFileIds += item.getString("id")
                        val motionId = item.optString("motionVideoPath").takeIf { it.isNotEmpty() }
                        if (motionId != null) {
                            val motionSha = item.optString("motionVideoSha256")
                            val motionLen = item.optLong("motionVideoByteLength", 0)
                            check(motionSha.isNotEmpty() && motionLen > 0) { "动态照片附属视频元数据无效" }
                            check(expectedIds.put(motionId, motionSha to motionLen) == null) { "附件 ID 重复" }
                            if (item.getString("entryId") in retainedEntryIds) retainedFileIds += motionId
                        }
                    }
                    for (i in 0 until audio.length()) {
                        val item = audio.getJSONObject(i)
                        check(expectedIds.put(item.getString("id"), item.getString("sha256") to item.getLong("byteLength")) == null) { "附件 ID 重复" }
                        if (item.getString("entryId") in retainedEntryIds) retainedFileIds += item.getString("id")
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

    companion object {
        private val DISCARD = object : OutputStream() { override fun write(b: Int) = Unit }
    }
}
