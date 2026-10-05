package com.beyondguo.imprints.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MotionPhotoExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpegStub(): ByteArray {
        // 最小 JPEG 起始
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    }

    private fun mp4Stub(totalSize: Int): ByteArray {
        // [size BE][ftyp][brand] + 填充
        val brand = "isom".toByteArray(Charsets.US_ASCII)
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(totalSize)
        buf[0] = ((totalSize ushr 24) and 0xff).toByte()
        buf[1] = ((totalSize ushr 16) and 0xff).toByte()
        buf[2] = ((totalSize ushr 8) and 0xff).toByte()
        buf[3] = (totalSize and 0xff).toByte()
        ftyp.copyInto(buf, 4)
        brand.copyInto(buf, 8)
        return buf
    }

    @Test fun extractsTrailingMp4FromJpeg() {
        val jpeg = jpegStub()
        val mp4 = mp4Stub(4096)
        val src = tmp.newFile("motion.jpg")
        src.outputStream().use { it.write(jpeg); it.write(mp4) }
        val dest = File(tmp.root, "out.mp4")
        val result = MotionPhotoExtractor.extractEmbeddedVideo(src, dest)
        assertTrue(result != null)
        assertTrue(dest.isFile)
        assertTrue(dest.length() >= 4096)
        // 抽出的应是 MP4 头
        val head = ByteArray(8)
        dest.inputStream().use { it.read(head) }
        assertTrue(String(head, 4, 4, Charsets.US_ASCII) == "ftyp")
    }

    @Test fun plainJpegWithoutVideoReturnsNull() {
        val src = tmp.newFile("plain.jpg")
        src.outputStream().use { it.write(jpegStub()); it.write(ByteArray(2048)) }
        val dest = File(tmp.root, "none.mp4")
        val result = MotionPhotoExtractor.extractEmbeddedVideo(src, dest)
        assertFalse(result != null)
        assertFalse(dest.exists())
    }

    @Test fun nonJpegReturnsNull() {
        val src = tmp.newFile("x.png")
        src.outputStream().use {
            it.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            it.write(ByteArray(4096))
        }
        val dest = File(tmp.root, "y.mp4")
        assertFalse(MotionPhotoExtractor.extractEmbeddedVideo(src, dest) != null)
    }
}
