package com.beyondguo.imprints.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class PreparedMedia(
    val id: String,
    val pendingFile: File,
    val kind: String,
    val mime: String,
    val byteLength: Long,
    val sha256: String,
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
            PreparedMedia(id, file, kind, declaredMime, length, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun commit(prepared: PreparedMedia): String {
        val target = resolve(prepared.id)
        check(prepared.pendingFile.renameTo(target)) { "媒体文件提交失败" }
        return prepared.id
    }

    fun discard(prepared: PreparedMedia) {
        prepared.pendingFile.delete()
    }

    suspend fun cleanup(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val staleBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        pending.listFiles()?.forEach { if (it.isFile && it.lastModified() < staleBefore) it.delete() }
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in referenced) file.delete()
        }
    }
}
