package com.beyondguo.imprints.backup

import com.beyondguo.penly.crypto.Aead
import com.beyondguo.penly.crypto.Profile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private const val MAGIC = "IMPRINTS-BACKUP"
private const val VERSION = 1
private const val CHUNK = 1024 * 1024

internal data class BackupHeader(val uuid: String, val salt: ByteArray, val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    fun key(password: CharArray): ByteArray {
        val root = Aead.argon2id(String(password).toByteArray(Charsets.UTF_8), salt, memoryKiB, iterations, parallelism)
        return try { Aead.subKey(root, "imprints-backup-v1") } finally { root.fill(0) }
    }
}

internal fun createHeader(out: DataOutputStream): BackupHeader {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val header = BackupHeader(UUID.randomUUID().toString(), salt, Profile.SENSITIVE.memoryKiB, Profile.SENSITIVE.iterations, Profile.SENSITIVE.parallelism)
    out.writeUTF(MAGIC)
    out.writeInt(VERSION)
    out.writeUTF(header.uuid)
    out.writeInt(salt.size)
    out.write(salt)
    out.writeInt(header.memoryKiB)
    out.writeInt(header.iterations)
    out.writeInt(header.parallelism)
    return header
}

internal fun readHeader(input: DataInputStream): BackupHeader {
    require(input.readUTF() == MAGIC && input.readInt() == VERSION) { "不支持的备份格式" }
    val uuid = input.readUTF()
    UUID.fromString(uuid)
    val saltLength = input.readInt()
    require(saltLength == 16) { "备份盐长度错误" }
    val salt = ByteArray(16).also { input.readFully(it) }
    val memory = input.readInt()
    val iterations = input.readInt()
    val parallelism = input.readInt()
    require(memory in 16 * 1024..256 * 1024 && iterations in 1..10 && parallelism in 1..4) { "备份 KDF 参数不受支持" }
    return BackupHeader(uuid, salt, memory, iterations, parallelism)
}

internal class EncryptedFrames(private val output: DataOutputStream, private val header: BackupHeader, private val key: ByteArray) : OutputStream() {
    private val buffer = ByteArray(CHUNK)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var used = 0
    private var sequence = 0L
    private var bytes = 0L
    private var finished = false

    override fun write(value: Int) {
        check(!finished)
        buffer[used++] = value.toByte()
        digest.update(value.toByte())
        bytes++
        if (used == CHUNK) flushChunk()
    }

    override fun write(values: ByteArray, offset: Int, length: Int) {
        check(!finished)
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, CHUNK - used)
            values.copyInto(buffer, used, pos, pos + count)
            digest.update(values, pos, count)
            used += count
            bytes += count
            pos += count
            remaining -= count
            if (used == CHUNK) flushChunk()
        }
    }

    private fun flushChunk() {
        if (used == 0) return
        val sealed = Aead.gcmEncrypt(key, buffer.copyOf(used), aad(sequence))
        output.writeInt(sealed.size)
        output.write(sealed)
        sequence++
        used = 0
    }

    fun finish(): ByteArray {
        check(!finished)
        flushChunk()
        finished = true
        val hash = digest.digest()
        val footer = java.io.ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { it.writeLong(bytes); it.write(hash) }
        }.toByteArray()
        val sealed = Aead.gcmEncrypt(key, footer, aad(sequence, end = true))
        output.writeInt(0)
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
        return hash
    }

    private fun aad(number: Long, end: Boolean = false) = "${header.uuid}|$VERSION|${if (end) "end|" else ""}$number".toByteArray(Charsets.UTF_8)
}

internal class DecryptedFrames(private val input: DataInputStream, private val header: BackupHeader, private val key: ByteArray) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var buffer = ByteArray(0)
    private var offset = 0
    private var sequence = 0L
    private var bytes = 0L
    private var done = false
    var verifiedHash: ByteArray? = null
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (offset == buffer.size) nextFrame()
        if (done) return -1
        val count = minOf(len, buffer.size - offset)
        buffer.copyInto(target, off, offset, offset + count)
        digest.update(buffer, offset, count)
        bytes += count
        offset += count
        return count
    }

    private fun nextFrame() {
        val length = input.readInt()
        require(length in 0..(CHUNK + 28)) { "备份分块长度错误" }
        if (length == 0) {
            val footerLength = input.readInt()
            require(footerLength in 28..256) { "备份尾记录错误" }
            val sealed = ByteArray(footerLength).also { input.readFully(it) }
            val footer = Aead.gcmDecrypt(key, sealed, aad(sequence, true))
            require(footer.size == 40) { "备份尾记录长度错误" }
            val expectedBytes = java.nio.ByteBuffer.wrap(footer, 0, 8).long
            val expectedHash = footer.copyOfRange(8, 40)
            val actualHash = digest.digest()
            require(expectedBytes == bytes && MessageDigest.isEqual(expectedHash, actualHash)) { "备份不完整" }
            require(input.read() == -1) { "备份尾部存在额外数据" }
            verifiedHash = actualHash
            done = true
            return
        }
        require(length >= 28) { "备份分块长度错误" }
        val sealed = ByteArray(length).also { input.readFully(it) }
        buffer = Aead.gcmDecrypt(key, sealed, aad(sequence))
        require(buffer.isNotEmpty() && buffer.size <= CHUNK)
        offset = 0
        sequence++
    }

    private fun aad(number: Long, end: Boolean = false) = "${header.uuid}|$VERSION|${if (end) "end|" else ""}$number".toByteArray(Charsets.UTF_8)
}
