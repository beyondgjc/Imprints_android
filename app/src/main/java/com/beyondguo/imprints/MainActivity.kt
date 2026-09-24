package com.beyondguo.imprints

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.beyondguo.imprints.data.ImprintDatabase
import com.beyondguo.imprints.backup.BackupService
import com.beyondguo.imprints.data.ImprintRepository
import com.beyondguo.imprints.data.MediaFiles
import com.beyondguo.imprints.security.ImprintSecurity
import com.beyondguo.imprints.ui.ImprintsApp
import com.beyondguo.imprints.ui.theme.ImprintsTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class MainActivity : FragmentActivity() {
    private val database by lazy { ImprintDatabase.get(this) }
    private val security by lazy { ImprintSecurity(database) }
    private val repository by lazy { ImprintRepository(database, MediaFiles(this), security) }
    private var appLocked = androidx.compose.runtime.mutableStateOf(true)
    private var initialized = androidx.compose.runtime.mutableStateOf(false)
    private var lockEnabled = false
    private var revealEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // 防截屏 + 多任务隐藏：同一开关（FLAG_SECURE），默认开
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
        lifecycleScope.launch {
            lockEnabled = security.state().appLockHeaderJson != null
            appLocked.value = lockEnabled
            initialized.value = true
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "imprints-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<com.beyondguo.imprints.data.CleanupWorker>(1, TimeUnit.DAYS).build(),
        )
        setContent {
            ImprintsTheme {
                ImprintsApp(
                    repository = repository,
                    initialized = initialized.value,
                    locked = appLocked.value,
                    revealEpoch = revealEpoch.intValue,
                    unlockApp = { pin ->
                        val ok = security.verifyAppLock(pin)
                        if (ok) appLocked.value = false
                        ok
                    },
                    refreshLockState = {
                        lockEnabled = security.state().appLockHeaderJson != null
                        if (lockEnabled) {
                            security.clearFeelingSession()
                            revealEpoch.intValue++
                        }
                        appLocked.value = lockEnabled
                    },
                    requestBiometric = { target, onSuccess, onUnavailable ->
                        promptBiometric(target, onSuccess, onUnavailable)
                    },
                    onPrivacyFlagsChanged = {
                        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            runCatching { repository.cleanupExpired() }
            runCatching { BackupService(this@MainActivity, database, repository).cleanupInterruptedImports() }
        }
    }

    override fun onStop() {
        super.onStop()
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
        security.clearFeelingSession()
        revealEpoch.intValue++
        if (lockEnabled) appLocked.value = true
    }

    private fun promptBiometric(target: String, onSuccess: () -> Unit, onUnavailable: () -> Unit) {
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            onUnavailable()
            return
        }
        lifecycleScope.launch {
            val cipher: Cipher? = when (target) {
                "feeling-unlock" -> runCatching { security.biometricUnlockCipher() }.getOrNull()
                "feeling-enable" -> runCatching { security.biometricEnrollmentCipher() }.getOrNull()
                else -> null
            }
            if (target.startsWith("feeling") && cipher == null) {
                onUnavailable()
                return@launch
            }
            val prompt = BiometricPrompt(this@MainActivity, ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lifecycleScope.launch {
                            val worked = runCatching {
                                when (target) {
                                    "feeling-unlock" -> security.finishBiometricUnlock(result.cryptoObject?.cipher ?: error("缺少认证 Cipher"))
                                    "feeling-enable" -> security.finishBiometricEnrollment(result.cryptoObject?.cipher ?: error("缺少认证 Cipher"))
                                    "app-unlock" -> appLocked.value = false
                                    "app-enable" -> security.setAppLockBiometricEnabled(true)
                                    "app-change" -> Unit
                                }
                            }.isSuccess
                            if (worked) onSuccess() else onUnavailable()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onUnavailable()
                    }
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (target.startsWith("feeling")) "验证感受" else "解锁印迹")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("使用 6 位口令")
                .build()
            if (cipher == null) prompt.authenticate(info)
            else prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
