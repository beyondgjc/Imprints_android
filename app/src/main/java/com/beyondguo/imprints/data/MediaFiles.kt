package com.beyondguo.imprints.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID


/**
 * 一个媒体项（图片 / 动图 / 视频 / 声音）。
 *
 * **刻意不依赖任何业务实体**：媒体不知道自己属于印迹、信件还是回顾 ——
 * 谁引用它，谁在自己的行里记这个路径。`VisualMediaEntity` / `AudioEntity` 只是"引用方"，
 * 它们才需要知道 owner 是谁。
 *
 * 这样编辑印迹、写信、写回顾可以共用同一个媒体对象与同一套媒体网格。
 * 动态照片的附属视频是同一个"项"的组成部分，不单独算一个媒体。
 */
data class MediaItem(
    val path: String,
    val kind: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    val motionVideoPath: String? = null,
    val motionVideoSha256: String? = null,
    val motionVideoByteLength: Long? = null,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("path", path).put("kind", kind).put("mime", mime)
        .put("byteLength", byteLength).put("sha256", sha256)
        .put("motionVideoPath", motionVideoPath ?: "")
        .put("motionVideoSha256", motionVideoSha256 ?: "")
        .put("motionVideoByteLength", motionVideoByteLength ?: 0L)

    companion object {
        fun fromJson(j: org.json.JSONObject) = MediaItem(
            path = j.getString("path"),
            kind = j.getString("kind"),
            mime = j.getString("mime"),
            byteLength = j.getLong("byteLength"),
            sha256 = j.getString("sha256"),
            motionVideoPath = j.optString("motionVideoPath").takeIf { it.isNotEmpty() },
            motionVideoSha256 = j.optString("motionVideoSha256").takeIf { it.isNotEmpty() },
            motionVideoByteLength = j.optLong("motionVideoByteLength").takeIf { it > 0 },
        )

        /** 从已存路径还原（表里只记路径时用）：按后缀推断类型。 */
        fun of(path: String): MediaItem {
            val ext = path.substringAfterLast('.', "").lowercase()
            val (kind, mime) = when (ext) {
                "m4a", "aac" -> "AUDIO" to "audio/mp4"
                "mp3" -> "AUDIO" to "audio/mpeg"
                "wav" -> "AUDIO" to "audio/wav"
                "ogg" -> "AUDIO" to "audio/ogg"
                "gif" -> "GIF" to "image/gif"
                "png" -> "IMAGE" to "image/png"
                "webp" -> "IMAGE" to "image/webp"
                "mp4" -> "VIDEO" to "video/mp4"
                else -> "IMAGE" to "image/jpeg"
            }
            return MediaItem(path, kind, mime, 0L, "")
        }
    }
}

fun VisualMediaEntity.toMediaItem() = MediaItem(
    path = relativePath, kind = kind, mime = mime, byteLength = byteLength, sha256 = sha256,
    motionVideoPath = motionVideoPath, motionVideoSha256 = motionVideoSha256,
    motionVideoByteLength = motionVideoByteLength,
)

fun AudioEntity.toMediaItem() = MediaItem(
    path = relativePath, kind = "AUDIO", mime = mime, byteLength = byteLength, sha256 = sha256,
)

data class PreparedMedia(
    val id: String,
    val pendingFile: File,
    val kind: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
    /** 动态照片抽出的附属视频（pending 目录）；提交时一并 rename */
    val motionVideoId: String? = null,
    val motionVideoPending: File? = null,
    val motionVideoSha256: String? = null,
    val motionVideoByteLength: Long? = null,
)

class MediaFiles(private val context: Context) {
    val root: File = File(context.filesDir, "media").also { it.mkdirs() }
    private val pending: File = File(root, ".pending").also { it.mkdirs() }

    fun resolve(relativePath: String): File {
        require(relativePath.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid media path" }
        return File(root, relativePath)
    }

    suspend fun prepare(uri: Uri, audio: Boolean = false): PreparedMedia = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = File(pending, id)
        val declaredMime = context.contentResolver.getType(uri)?.lowercase() ?: "application/octet-stream"
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        length += count
                    }
                    output.fd.sync()
                }
            } ?: error("无法打开所选文件")
            require(length > 0) { "空文件无法添加" }
            val header = file.inputStream().use { stream ->
                val bytes = ByteArray(32)
                bytes.copyOf(stream.read(bytes).coerceAtLeast(0))
            }
            val kind = MediaKindDetector.detect(header, declaredMime, audio)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }

            // 动态照片：识别成功则抽附属视频；失败降级为普通图片（PRD V1.29）
            var motionId: String? = null
            var motionPending: File? = null
            var motionSha: String? = null
            var motionLen: Long? = null
            if (kind == "IMAGE" && !audio) {
                val mid = UUID.randomUUID().toString()
                val mdest = File(pending, mid)
                val result = MotionPhotoExtractor.extractEmbeddedVideo(file, mdest)
                if (result != null) {
                    motionId = mid
                    motionPending = mdest
                    motionSha = result.sha256
                    motionLen = result.byteLength
                } else {
                    mdest.delete()
                }
            }

            PreparedMedia(
                id = id,
                pendingFile = file,
                kind = kind,
                mime = declaredMime,
                byteLength = length,
                sha256 = sha,
                motionVideoId = motionId,
                motionVideoPending = motionPending,
                motionVideoSha256 = motionSha,
                motionVideoByteLength = motionLen,
            )
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** 提交主图；若有附属动态视频一并从 pending 挪到正式目录。返回主图相对路径。 */
    fun commit(prepared: PreparedMedia): String {
        val target = resolve(prepared.id)
        check(prepared.pendingFile.renameTo(target)) { "媒体文件提交失败" }
        val motionId = prepared.motionVideoId
        val motionFile = prepared.motionVideoPending
        if (motionId != null && motionFile != null) {
            val motionTarget = resolve(motionId)
            if (!motionFile.renameTo(motionTarget)) {
                // 附属视频失败不阻断主图：降级为普通图片
                motionTarget.delete()
            }
        }
        return prepared.id
    }

    fun discard(prepared: PreparedMedia) {
        prepared.pendingFile.delete()
        prepared.motionVideoPending?.delete()
    }

    suspend fun cleanup(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val staleBefore = now - 24L * 60 * 60 * 1000
        pending.listFiles()?.forEach { if (it.isFile && it.lastModified() < staleBefore) it.delete() }
        root.listFiles()?.forEach { file ->
            // 「刚放进来、引用还没登记」是一个真实存在的窗口期：选图后文件已落盘，
            // 但 media_references 要等保存或存草稿才写。这段时间里任何清理都会误删。
            // 1 小时内写入的文件一律跳过；同时把每次删除打出来，便于定位是谁触发的。
            val fresh = now - file.lastModified() < 60L * 60 * 1000
            if (file.isFile && file.name !in referenced && !fresh) {
                android.util.Log.w("MediaCleanup", "delete ${file.name} refs=${referenced.size}")
                file.delete()
            }
        }
    }
}
