package com.beyondguo.imprints.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BackupFramesTest {
    private val password = "backup-test-password".toCharArray()

    private fun archive(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        val header = createHeader(data)
        val frames = EncryptedFrames(data, header, header.key(password))
        frames.write(body)
        frames.finish()
        return output.toByteArray()
    }

    private fun open(bytes: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val header = readHeader(input)
        return DecryptedFrames(input, header, header.key(password)).readBytes()
    }

    @Test fun roundTripAcrossChunks() {
        val body = ByteArray(1_100_000) { (it % 251).toByte() }
        assertArrayEquals(body, open(archive(body)))
    }

    @Test fun truncationFailsAuthentication() {
        val bytes = archive("hello".toByteArray())
        assertThrows(Exception::class.java) { open(bytes.copyOf(bytes.size - 8)) }
    }

    @Test fun modifiedCiphertextFailsAuthentication() {
        val bytes = archive("hello".toByteArray())
        bytes[bytes.size - 48] = (bytes[bytes.size - 48].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { open(bytes) }
    }

    @Test fun wrongPasswordFailsAuthentication() {
        val input = DataInputStream(ByteArrayInputStream(archive("hello".toByteArray())))
        val header = readHeader(input)
        assertThrows(Exception::class.java) {
            DecryptedFrames(input, header, header.key("wrong-password".toCharArray())).readBytes()
        }
    }

    @Test fun trailingBytesFailAuthentication() {
        assertThrows(Exception::class.java) { open(archive("hello".toByteArray()) + byteArrayOf(1)) }
    }
}
