package com.beyondguo.imprints.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Android Motion Photo（动态照片）识别与内嵌 MP4 抽取。
 * 常见实现：JPEG/HEIC 主图内嵌或尾部附带短视频（XMP Motion Photo / MicroVideo 或文件尾 ftyp）。
 * 检测失败返回 null，调用方降级为普通图片（PRD V1.29）。
 */
object MotionPhotoExtractor {

    /** 返回非 null 表示识别为动态照片并抽出附属视频到输出文件 */
    fun extractEmbeddedVideo(source: File, dest: File): MotionExtractResult? {
        return runCatching {
            if (!source.isFile || source.length() < 1024) return@runCatching null
            val header = ByteArray(64)
            source.inputStream().use { it.read(header) }
            // 仅处理 JPEG 主图（HEIC 内嵌差异大，首版可后续扩展）
            val isJpeg = header.size >= 3 &&
                (header[0].toInt() and 0xff) == 0xff &&
                (header[1].toInt() and 0xff) == 0xd8
            if (!isJpeg) return@runCatching null

            val xmp = readXmpSnippet(source)
            val hasMarker = xmp != null && (
                xmp.contains("MotionPhoto") ||
                    xmp.contains("MicroVideo") ||
                    xmp.contains("GCamera:MotionPhoto") ||
                    xmp.contains("Camera:MotionPhoto")
                )

            val videoOffset = findMp4Start(source)
                ?: if (hasMarker) findMp4StartByScan(source) else null
            if (videoOffset == null || videoOffset <= 0) return@runCatching null
            // 必须在文件中部之后开始（前面应有主图），且视频段足够长
            val videoLen = source.length() - videoOffset
            if (videoLen < 512) return@runCatching null
            if (videoOffset < 4) return@runCatching null
            // 主图至少占前 4 字节（SOI 之后）；允许小 stub 图 + 尾部视频
            if (videoOffset > source.length() - 512) return@runCatching null

            dest.outputStream().use { out ->
                RandomAccessFile(source, "r").use { raf ->
                    raf.seek(videoOffset)
                    val buf = ByteArray(64 * 1024)
                    var remain = videoLen
                    while (remain > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remain -= n
                    }
                }
            }
            if (!dest.isFile || dest.length() < 512) {
                dest.delete()
                return@runCatching null
            }
            val sha = sha256(dest)
            MotionExtractResult(dest.length(), sha, hasMarker)
        }.getOrNull()
    }

    data class MotionExtractResult(val byteLength: Long, val sha256: String, val xmpMarker: Boolean)

    /** 在文件前 256KB 查找 XMP 片段 */
    private fun readXmpSnippet(file: File): String? {
        val cap = minOf(file.length(), 256L * 1024).toInt()
        if (cap <= 0) return null
        val bytes = ByteArray(cap)
        file.inputStream().use { stream ->
            var off = 0
            while (off < cap) {
                val n = stream.read(bytes, off, cap - off)
                if (n <= 0) break
                off += n
            }
        }
        // 常见标记用 ASCII 搜索，避免整包 decode
        val text = String(bytes, Charsets.ISO_8859_1)
        return if (text.contains("MotionPhoto") || text.contains("MicroVideo") ||
            text.contains("http://ns.google.com/photos/1.0/camera/") ||
            text.contains("http://ns.google.com/videos/1.0/microvideo/")
        ) text else null
    }

    /** 从尾部向前找 MP4 `ftyp` box，返回文件偏移（box 起点） */
    private fun findMp4Start(file: File): Long? {
        val length = file.length()
        if (length < 1024) return null
        val window = minOf(length, 2L * 1024 * 1024)
        val buf = ByteArray(window.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - window)
            raf.readFully(buf)
        }
        // 从后往前找 "ftyp"
        var i = buf.size - 8
        while (i >= 4) {
            if (buf[i] == 'f'.code.toByte() && buf[i + 1] == 't'.code.toByte() &&
                buf[i + 2] == 'y'.code.toByte() && buf[i + 3] == 'p'.code.toByte()
            ) {
                val boxStart = i - 4
                if (boxStart >= 0) {
                    val boxSize = readUInt32(buf, boxStart)
                    val abs = (length - window) + boxStart
                    // size==1 时为 64-bit largesize，跳过简单路径
                    if (boxSize in 16..window && abs > 0 && abs < length) {
                        return abs
                    }
                    // size 为 0 表示直到文件尾：用 boxStart 到 EOF
                    if (boxSize == 0L && abs > 0) return abs
                }
            }
            i--
        }
        return null
    }

    /** 无明确 box size 时，扫描 EOI 后是否紧跟 MP4（弱启发） */
    private fun findMp4StartByScan(file: File): Long? {
        val length = file.length()
        val scanFrom = maxOf(0L, length - 512L * 1024)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(scanFrom)
            val buf = ByteArray((length - scanFrom).toInt())
            raf.readFully(buf)
            var i = 0
            while (i + 8 < buf.size) {
                if (buf[i + 4] == 'f'.code.toByte() && buf[i + 5] == 't'.code.toByte() &&
                    buf[i + 6] == 'y'.code.toByte() && buf[i + 7] == 'p'.code.toByte()
                ) {
                    return scanFrom + i
                }
                i++
            }
        }
        return null
    }

    private fun readUInt32(buf: ByteArray, at: Int): Long {
        if (at + 4 > buf.size) return -1
        return ((buf[at].toLong() and 0xff) shl 24) or
            ((buf[at + 1].toLong() and 0xff) shl 16) or
            ((buf[at + 2].toLong() and 0xff) shl 8) or
            (buf[at + 3].toLong() and 0xff)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
