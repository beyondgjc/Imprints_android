package com.beyondguo.imprints.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.beyondguo.imprints.data.ImprintDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 应用锁口令与感受加密（依赖 Android Keystore，须真机/模拟器）。 */
@RunWith(AndroidJUnit4::class)
class ImprintSecurityTest {
    private lateinit var db: ImprintDatabase
    private lateinit var security: ImprintSecurity

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImprintDatabase::class.java).build()
        security = ImprintSecurity(db)
    }

    @After
    fun tearDown() {
        security.clearFeelingSession()
        db.close()
    }

    @Test
    fun appLockSetVerifyAndWrongPin() = runBlocking {
        security.setAppLock("666666")
        assertTrue(security.verifyAppLock("666666"))
        assertFalse(security.verifyAppLock("654321"))
        // 失败后正确口令仍可解（未达退避阈值）
        assertTrue(security.verifyAppLock("666666"))
        assertEquals(0, security.state().appLockFailedAttempts)
    }

    @Test
    fun appLockRequiresSixDigits() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { security.setAppLock("12345") }
        }
    }

    @Test
    fun disableAppLockClearsVerifier() = runBlocking {
        security.setAppLock("666666")
        assertTrue(security.state().appLockHeaderJson != null)

        security.disableAppLock()
        val state = security.state()
        assertEquals(null, state.appLockHeaderJson)
        assertEquals(null, state.appLockVerifierB64)
        // 无锁时 verify 直接放行
        assertTrue(security.verifyAppLock("000000"))
    }

    @Test
    fun feelingEncryptRoundTripAndPlainWhenOff() = runBlocking {
        // 未开启加密：PLAIN
        val plain = security.encodeFeeling("entry-1", "晴天")
        assertEquals("PLAIN", plain!!.encoding)
        assertEquals("晴天", security.decodeFeeling(plain))

        // 开启感受加密 → FEK 会话 → 编解密
        security.enableFeeling("123456")
        assertEquals("ON", security.state().feelingMode)
        // enable 后会清会话，需重新解锁
        assertFalse(security.isFeelingUnlocked())
        assertTrue(security.unlockFeelingPin("123456"))

        val sealed = security.encodeFeeling("entry-1", "雨天带伞")
        assertEquals("GCM_V1", sealed!!.encoding)
        assertFalse(sealed.payload.contains("雨天"))
        assertEquals("雨天带伞", security.decodeFeeling(sealed))

        // 关闭加密需已解锁会话
        security.disableFeeling()
        assertEquals("OFF", security.state().feelingMode)
        assertFalse(security.isFeelingUnlocked())
    }

    @Test
    fun feelingWrongPinRejected() = runBlocking {
        security.enableFeeling("123456")
        assertFalse(security.unlockFeelingPin("654321"))
        assertTrue(security.unlockFeelingPin("123456"))
    }
}
