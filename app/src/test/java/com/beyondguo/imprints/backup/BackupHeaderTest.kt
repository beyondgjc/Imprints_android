package com.beyondguo.imprints.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BackupHeaderTest {
    private fun roundTrip(): Pair<BackupHeader, ByteArray> {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        val header = createHeader(data)
        return header to output.toByteArray()
    }

    @Test fun roundTripPreservesUuidAndKdf() {
        val (header, bytes) = roundTrip()
        val read = readHeader(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(header.uuid, read.uuid)
        assertEquals(header.memoryKiB, read.memoryKiB)
        assertEquals(header.iterations, read.iterations)
        assertEquals(header.parallelism, read.parallelism)
        assertEquals(header.salt.toList(), read.salt.toList())
    }

    @Test fun keyDerivationIsDeterministicPerHeader() {
        val (header, _) = roundTrip()
        val first = header.key("password-1".toCharArray())
        val second = header.key("password-1".toCharArray())
        val wrong = header.key("password-2".toCharArray())
        assertEquals(first.toList(), second.toList())
        org.junit.Assert.assertFalse(first.contentEquals(wrong))
    }

    @Test fun rejectsBadMagicVersionAndSaltLength() {
        val (header, bytes) = roundTrip()

        // 合法 UTF 但 magic 不匹配
        val badMagic = bytes.copyOf()
        badMagic[2] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            readHeader(DataInputStream(ByteArrayInputStream(badMagic)))
        }

        val badVersion = bytes.copyOf()
        val versionOffset = 2 + "IMPRINTS-BACKUP".length
        badVersion[versionOffset + 3] = 9
        assertThrows(IllegalArgumentException::class.java) {
            readHeader(DataInputStream(ByteArrayInputStream(badVersion)))
        }

        val badSalt = bytes.copyOf()
        val saltSizeOffset = versionOffset + 4 + (2 + header.uuid.length)
        badSalt[saltSizeOffset + 3] = 8
        assertThrows(IllegalArgumentException::class.java) {
            readHeader(DataInputStream(ByteArrayInputStream(badSalt)))
        }
        assertEquals(16, header.salt.size)
    }
}
