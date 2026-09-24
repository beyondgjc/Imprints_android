package com.beyondguo.imprints.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import com.beyondguo.imprints.data.FeelingEntity
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.data.SecurityStateEntity
import com.beyondguo.penly.crypto.Aead
import com.beyondguo.penly.crypto.Container
import com.beyondguo.penly.crypto.FieldCipher
import com.beyondguo.penly.crypto.KdfSpec
import com.beyondguo.penly.crypto.Profile
import com.beyondguo.penly.crypto.VaultHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ImprintSecurity(private val database: ImprintDatabase) {
    private val dao = database.dao()
    private val random = SecureRandom()
    private var feelingKey: ByteArray? = null

    suspend fun state(): SecurityStateEntity = dao.security() ?: SecurityStateEntity()
    fun clearFeelingSession() { feelingKey?.fill(0); feelingKey = null }
    fun isFeelingUnlocked(): Boolean = feelingKey != null

    suspend fun encodeFeeling(entryId: String, text: String): FeelingEntity? {
        if (text.isBlank()) return null
        return if (state().feelingMode == "ON") {
            val key = feelingKey ?: throw IllegalStateException("请先解锁感受")
            FeelingEntity(entryId, "GCM_V1", FieldCipher.seal(key, FieldCipher.aad("feeling", entryId), text.toByteArray(Charsets.UTF_8)))
        } else FeelingEntity(entryId, "PLAIN", text)
    }

    suspend fun decodeFeeling(value: FeelingEntity?): String? {
        if (value == null) return null
        if (value.encoding == "PLAIN") {
            check(state().feelingMode == "OFF") { "感受模式不一致" }
            return value.payload
        }
        check(value.encoding == "GCM_V1" && state().feelingMode == "ON") { "未知的感受格式" }
        val key = feelingKey ?: throw IllegalStateException("请先解锁感受")
        return String(FieldCipher.open(key, FieldCipher.aad("feeling", value.entryId), value.payload), Charsets.UTF_8)
    }

    suspend fun enableFeeling(pin: String) {
        requirePin(pin)
        check(state().feelingMode == "OFF")
        val header = Container.createHeader(Profile.SENSITIVE)
        val key = ByteArray(32).also(random::nextBytes)
        val envelope = pinEnvelope(header, pin, key)
        val converted = dao.allFeelings().map { current ->
            check(current.encoding == "PLAIN") { "感受模式不一致" }
            FeelingEntity(current.entryId, "GCM_V1", FieldCipher.seal(key, FieldCipher.aad("feeling", current.entryId), current.payload.toByteArray(Charsets.UTF_8)))
        }
        database.withTransaction {
            check((dao.security() ?: SecurityStateEntity()).feelingMode == "OFF")
            converted.forEach { dao.upsertFeeling(it) }
            dao.upsertSecurity(state().copy(feelingMode = "ON", feelingHeaderJson = headerJson(header), feelingPinEnvelope = envelope))
        }
        // 开启成功后不保持会话：时间线/详情需再次验证才能刮开（PRD：查看前先验证）
        clearFeelingSession()
    }

    suspend fun unlockFeelingPin(pin: String): Boolean {
        requirePin(pin)
        val current = state()
        check(current.feelingMode == "ON")
        if (System.currentTimeMillis() < current.feelingBlockedUntilMs) return false
        val header = parseHeader(current.feelingHeaderJson ?: error("缺少感受 KDF 参数"))
        val envelope = current.feelingPinEnvelope ?: error("缺少感受密钥")
        return try {
            val inner = unwrap(DEVICE_ALIAS, envelope)
            val kek = withContext(Dispatchers.Default) { Container.deriveKey(header, pin.toByteArray(Charsets.UTF_8)) }
            val key = FieldCipher.open(kek, FieldCipher.aad("imprints-fek", "v1"), String(inner, Charsets.UTF_8))
            require(key.size == 32)
            clearFeelingSession()
            feelingKey = key
            dao.upsertSecurity(current.copy(feelingFailedAttempts = 0, feelingBlockedUntilMs = 0))
            true
        } catch (_: Exception) {
            val attempts = current.feelingFailedAttempts + 1
            dao.upsertSecurity(current.copy(feelingFailedAttempts = attempts, feelingBlockedUntilMs = blockedUntil(attempts)))
            false
        }
    }

    suspend fun disableFeeling() {
        val key = feelingKey ?: throw IllegalStateException("请先解锁感受")
        check(state().feelingMode == "ON")
        val converted = dao.allFeelings().map { current ->
            check(current.encoding == "GCM_V1")
            FeelingEntity(current.entryId, "PLAIN", String(FieldCipher.open(key, FieldCipher.aad("feeling", current.entryId), current.payload), Charsets.UTF_8))
        }
        database.withTransaction {
            check(state().feelingMode == "ON")
            converted.forEach { dao.upsertFeeling(it) }
            dao.upsertSecurity(state().copy(feelingMode = "OFF", feelingHeaderJson = null, feelingPinEnvelope = null, feelingBiometricEnvelope = null))
        }
        clearFeelingSession()
    }

    suspend fun resetFeelingPin(newPin: String) {
        requirePin(newPin)
        val key = feelingKey ?: throw IllegalStateException("请先解锁感受")
        val header = Container.createHeader(Profile.SENSITIVE)
        dao.upsertSecurity(state().copy(feelingHeaderJson = headerJson(header), feelingPinEnvelope = pinEnvelope(header, newPin, key)))
    }

    fun biometricEnrollmentCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(BIOMETRIC_ALIAS, true))
        return cipher
    }

    suspend fun finishBiometricEnrollment(cipher: Cipher) {
        val key = feelingKey ?: throw IllegalStateException("请先解锁感受")
        val blob = Aead.b64(cipher.iv + cipher.doFinal(key))
        dao.upsertSecurity(state().copy(feelingBiometricEnvelope = blob))
    }

    suspend fun disableFeelingBiometric() {
        dao.upsertSecurity(state().copy(feelingBiometricEnvelope = null))
    }

    suspend fun biometricUnlockCipher(): Cipher {
        val blob = Aead.unb64(state().feelingBiometricEnvelope ?: error("感受生物识别尚未启用"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(BIOMETRIC_ALIAS, true), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher
    }

    suspend fun finishBiometricUnlock(cipher: Cipher) {
        val blob = Aead.unb64(state().feelingBiometricEnvelope ?: error("缺少生物识别信封"))
        val key = cipher.doFinal(blob.copyOfRange(12, blob.size))
        require(key.size == 32)
        clearFeelingSession()
        feelingKey = key
    }

    suspend fun setAppLock(pin: String) {
        requirePin(pin)
        val header = Container.createHeader(Profile.INTERACTIVE)
        val derived = withContext(Dispatchers.Default) { Container.deriveKey(header, pin.toByteArray(Charsets.UTF_8)) }
        val verifier = verifier(derived)
        derived.fill(0)
        dao.upsertSecurity(state().copy(appLockHeaderJson = headerJson(header), appLockVerifierB64 = Aead.b64(verifier), appLockFailedAttempts = 0, appLockBlockedUntilMs = 0))
    }

    suspend fun verifyAppLock(pin: String): Boolean {
        requirePin(pin)
        val current = state()
        if (System.currentTimeMillis() < current.appLockBlockedUntilMs) return false
        val header = current.appLockHeaderJson ?: return true
        val derived = withContext(Dispatchers.Default) { Container.deriveKey(parseHeader(header), pin.toByteArray(Charsets.UTF_8)) }
        val match = MessageDigest.isEqual(verifier(derived), Aead.unb64(current.appLockVerifierB64 ?: error("缺少应用锁校验值")))
        derived.fill(0)
        val attempts = if (match) 0 else current.appLockFailedAttempts + 1
        dao.upsertSecurity(current.copy(appLockFailedAttempts = attempts, appLockBlockedUntilMs = if (match) 0 else blockedUntil(attempts)))
        return match
    }

    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        dao.upsertSecurity(state().copy(appLockBiometricEnabled = enabled))
    }

    suspend fun disableAppLock() {
        dao.upsertSecurity(state().copy(appLockHeaderJson = null, appLockVerifierB64 = null,
            appLockBiometricEnabled = false, appLockFailedAttempts = 0, appLockBlockedUntilMs = 0))
    }

    private suspend fun pinEnvelope(header: VaultHeader, pin: String, key: ByteArray): String {
        val kek = withContext(Dispatchers.Default) { Container.deriveKey(header, pin.toByteArray(Charsets.UTF_8)) }
        val inner = FieldCipher.seal(kek, FieldCipher.aad("imprints-fek", "v1"), key).toByteArray(Charsets.UTF_8)
        kek.fill(0)
        return wrap(DEVICE_ALIAS, inner)
    }

    private fun verifier(derived: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("imprints-app-lock-v1".toByteArray(Charsets.UTF_8) + derived)

    private fun blockedUntil(attempts: Int): Long = PinPolicy.blockedUntil(attempts, System.currentTimeMillis())

    private fun requirePin(pin: String) = PinPolicy.requireValid(pin)

    private fun wrap(alias: String, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias, false))
        return Aead.b64(cipher.iv + cipher.doFinal(data))
    }

    private fun unwrap(alias: String, encoded: String): ByteArray {
        val blob = Aead.unb64(encoded)
        require(blob.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias, false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private fun key(alias: String, biometric: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(biometric)
        if (biometric) {
            if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else builder.setUserAuthenticationValidityDurationSeconds(-1)
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }.generateKey()
    }

    private fun headerJson(header: VaultHeader): String = JSONObject()
        .put("v", header.headerV).put("alg", header.dataAlg).put("kdf", header.kdf.alg)
        .put("salt", header.kdf.saltB64).put("m", header.kdf.memoryKiB)
        .put("t", header.kdf.iterations).put("p", header.kdf.parallelism).toString()

    private fun parseHeader(raw: String): VaultHeader {
        val json = JSONObject(raw)
        require(json.getInt("v") == Container.HEADER_V && json.getString("alg") == Container.DATA_ALG_AES_256_GCM)
        return VaultHeader(json.getInt("v"), KdfSpec(json.getString("kdf"), json.getString("salt"), json.getInt("m"), json.getInt("t"), json.getInt("p")), json.getString("alg"))
    }

    companion object {
        private const val DEVICE_ALIAS = "imprints_feeling_device_v1"
        private const val BIOMETRIC_ALIAS = "imprints_feeling_biometric_v1"
    }
}
