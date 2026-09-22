package com.beyondguo.imprints.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaKindDetectorTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun detectsCommonVisualFormats() {
        assertEquals("GIF", MediaKindDetector.detect("GIF89a....".toByteArray(), "image/gif", false))
        assertEquals("IMAGE", MediaKindDetector.detect(bytes(0xff, 0xd8, 0xff, 0xe0), "image/jpeg", false))
        assertEquals("IMAGE", MediaKindDetector.detect(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), "image/png", false))
        assertEquals("IMAGE", MediaKindDetector.detect("RIFF0000WEBP".toByteArray(), "image/webp", false))
        assertEquals("IMAGE", MediaKindDetector.detect("....ftypheic....".toByteArray(), "image/heic", false))
        assertEquals("VIDEO", MediaKindDetector.detect("....ftypisom....".toByteArray(), "video/mp4", false))
        assertEquals("VIDEO", MediaKindDetector.detect(bytes(0x1a, 0x45, 0xdf, 0xa3), "video/webm", false))
    }

    @Test fun detectsAudioFormats() {
        assertEquals("AUDIO", MediaKindDetector.detect("ID3\u0003\u0000".toByteArray(), "audio/mpeg", true))
        assertEquals("AUDIO", MediaKindDetector.detect("RIFF0000WAVE".toByteArray(), "audio/wav", true))
        assertEquals("AUDIO", MediaKindDetector.detect("OggS....".toByteArray(), "audio/ogg", true))
        assertEquals("AUDIO", MediaKindDetector.detect("fLaC....".toByteArray(), "audio/flac", true))
    }

    @Test fun rejectsMismatchedOrUnknown() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaKindDetector.detect("GIF89a....".toByteArray(), "video/mp4", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaKindDetector.detect("not-a-media".toByteArray(), "application/octet-stream", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaKindDetector.detect("ID3\u0003\u0000".toByteArray(), "video/mp4", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaKindDetector.detect(bytes(0x00, 0x01), "audio/mpeg", true)
        }
    }
}
