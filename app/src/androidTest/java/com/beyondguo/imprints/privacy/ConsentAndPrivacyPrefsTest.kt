package com.beyondguo.imprints.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 首启同意状态与防截屏开关（SharedPreferences 持久化）。 */
@RunWith(AndroidJUnit4::class)
class ConsentAndPrivacyPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("imprints_consent", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("imprints_privacy", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("imprints_consent", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("imprints_privacy", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun consentDefaultsToNotAccepted() {
        assertFalse(ConsentState.isAccepted(context))
    }

    @Test
    fun consentAcceptPersistsAndRejectClears() {
        ConsentState.setAccepted(context, true)
        assertTrue(ConsentState.isAccepted(context))

        ConsentState.setAccepted(context, false)
        assertFalse(ConsentState.isAccepted(context))
    }

    @Test
    fun consentVersionMismatchRequiresReConsent() {
        ConsentState.setAccepted(context, true)
        assertTrue(ConsentState.isAccepted(context))

        // 模拟政策版本升级：已同意但版本号不是当前 POLICY_VERSION
        context.getSharedPreferences("imprints_consent", Context.MODE_PRIVATE)
            .edit()
            .putInt("policy_version", ConsentState.POLICY_VERSION + 1)
            .commit()
        assertFalse(ConsentState.isAccepted(context))
    }

    @Test
    fun privacySecureWindowDefaultsOnAndToggles() {
        assertTrue(PrivacyPreferences.isEnabled(context))

        PrivacyPreferences.setEnabled(context, false)
        assertFalse(PrivacyPreferences.isEnabled(context))

        PrivacyPreferences.setEnabled(context, true)
        assertTrue(PrivacyPreferences.isEnabled(context))
    }

    @Test
    fun policyAssetsAreReadable() {
        val privacy = ConsentState.readAsset(context, "privacy_policy.txt")
        val agreement = ConsentState.readAsset(context, "user_agreement.txt")
        assertFalse(privacy.contains("协议文件读取失败"))
        assertFalse(agreement.contains("协议文件读取失败"))
        assertTrue(privacy.contains("第三方"))
        assertTrue(agreement.contains("高德"))
    }
}
