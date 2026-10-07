package com.beyondguo.imprints

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : FragmentActivity() {
    // 隐私门控：为 false 时不创建 database/security/repository（by lazy 保证不被触碰），
    // 此时 Compose 树里只有同意弹窗，符合「同意前不收集任何个人信息」的要求。
    private var consentGranted by androidx.compose.runtime.mutableStateOf<Boolean?>(null)

    private val database by lazy { ImprintDatabase.get(this) }
    private val security by lazy { ImprintSecurity(database) }
    private val repository by lazy { ImprintRepository(database, MediaFiles(this), security) }
    private var appLocked = androidx.compose.runtime.mutableStateOf(true)
    private var initialized = androidx.compose.runtime.mutableStateOf(false)
    private var lockEnabled = false
    private var revealEpoch = androidx.compose.runtime.mutableIntStateOf(0)
    /** 生物识别弹窗期间不清感受会话，否则注册/解锁成功回调时 FEK 已被清掉 */
    private var biometricPromptActive = false
    /**
     * 正在跳系统 Activity（选图/选文件/分享等）的深度。
     * 这些跳转会触发 onStop，不能当成「离开应用」而上锁、清感受会话。
     */
    private var externalUiDepth = 0
    /** 用户已明确「不同意」：再点一次即退出，避免停在无功能可用的空界面上 */
    private var declinedAt by androidx.compose.runtime.mutableLongStateOf(0L)

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        externalUiDepth++
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (error: Exception) {
            externalUiDepth--
            throw error
        }
    }

    override fun startIntentSenderForResult(
        intent: android.content.IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
    ) {
        externalUiDepth++
        try {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags)
        } catch (error: Exception) {
            externalUiDepth--
            throw error
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // 防截屏 + 多任务隐藏：同一开关（FLAG_SECURE），默认开
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)

        // 同意状态只读 SharedPreferences，不触碰 Room/Keystore
        consentGranted = com.beyondguo.imprints.privacy.ConsentState.isAccepted(this)

        if (consentGranted == true) {
            startApp()
        }

        setContent {
            ImprintsTheme {
                val granted = consentGranted
                if (granted != true) {
                    com.beyondguo.imprints.ui.ConsentDialog(
                        onAgree = {
                            com.beyondguo.imprints.privacy.ConsentState.setAccepted(this, true)
                            consentGranted = true
                            startApp()
                        },
                        onDisagree = {
                            val now = System.currentTimeMillis()
                            // 首次点「不同意」不立刻退出：给一次反悔机会，2.5 秒内再点才退出
                            val last = declinedAt
                            if (now - last < 2500L) finishAndRemoveTask()
                            declinedAt = now
                        },
                    )
                } else {
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
                        onRevokeConsent = {
                            com.beyondguo.imprints.privacy.ConsentState.setAccepted(this, false)
                            consentGranted = false
                            declinedAt = 0L
                            // 撤回后直接退出：留着应用锁页面会让用户困在协议页里出不来
                            finishAndRemoveTask()
                        },
                    )
                }
            }
        }
    }

    /** 仅在已同意后调用：初始化数据库相关状态与后台清理任务。 */
    private fun startApp() {
        lifecycleScope.launch {
            lockEnabled = security.state().appLockHeaderJson != null
            appLocked.value = lockEnabled
            initialized.value = true
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "imprints-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<com.beyondguo.imprints.data.CleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    override fun onResume() {
        super.onResume()
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
    }

    override fun onStart() {
        super.onStart()
        // 同意前不碰数据库：repository/database 是 by lazy，访问即建库
        if (consentGranted != true) return
        lifecycleScope.launch {
            runCatching { repository.cleanupExpired() }
            runCatching { BackupService(this@MainActivity, database, repository).cleanupInterruptedImports() }
        }
    }

    override fun onStop() {
        super.onStop()
        com.beyondguo.imprints.privacy.PrivacyPreferences.apply(this)
        // 选图器等系统页：只是一层临时覆盖，回来仍应在编辑状态
        if (externalUiDepth > 0) {
            externalUiDepth--
            android.util.Log.i("ImprintsLock", "onStop skip lock (externalUi depth=$externalUiDepth)")
            return
        }
        // 同意前无会话可清，也无锁可上
        if (consentGranted != true) return
        // 指纹系统弹窗可能触发 onStop；此刻清会话会导致「刚输完口令开指纹却注册失败」
        if (!biometricPromptActive) {
            security.clearFeelingSession()
            revealEpoch.intValue++
        }
        if (lockEnabled) appLocked.value = true
    }

    private fun promptBiometric(target: String, onSuccess: () -> Unit, onUnavailable: () -> Unit) {
        val manager = BiometricManager.from(this)
        // MIUI/API29 上 BIOMETRIC_STRONG 常返回 STATUS_UNKNOWN(-1)，但指纹仍可用；
        // 只有明确「无硬件/未录入/硬件不可用」才拦截，未知状态照样尝试弹窗。
        val can = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val hardFail = can in setOf(
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        )
        android.util.Log.i("ImprintsBio", "promptBiometric target=$target canAuthenticate=$can hardFail=$hardFail")
        if (hardFail) {
            android.util.Log.e("ImprintsBio", "biometric unavailable can=$can")
            onUnavailable()
            return
        }
        lifecycleScope.launch {
            var cipher: Cipher? = null
            var cipherError: Throwable? = null
            when (target) {
                "feeling-unlock" -> {
                    val r = runCatching { security.biometricUnlockCipher() }
                    cipher = r.getOrNull(); cipherError = r.exceptionOrNull()
                }
                "feeling-enable" -> {
                    val r = runCatching { security.biometricEnrollmentCipher() }
                    cipher = r.getOrNull(); cipherError = r.exceptionOrNull()
                }
                else -> cipher = null
            }
            android.util.Log.i("ImprintsBio", "target=$target cipher=${cipher != null} cipherError=$cipherError")
            if (target.startsWith("feeling") && cipher == null) {
                android.util.Log.e("ImprintsBio", "feeling cipher unavailable", cipherError)
                onUnavailable()
                return@launch
            }
            val prompt = BiometricPrompt(this@MainActivity, ContextCompat.getMainExecutor(this@MainActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        lifecycleScope.launch {
                            try {
                                val error = runCatching {
                                    when (target) {
                                        "feeling-unlock" -> security.finishBiometricUnlock(result.cryptoObject?.cipher ?: error("缺少认证 Cipher"))
                                        "feeling-enable" -> {
                                            if (!security.isFeelingUnlocked()) error("感受会话已失效，请重新输入口令")
                                            security.finishBiometricEnrollment(result.cryptoObject?.cipher ?: error("缺少认证 Cipher"))
                                        }
                                        "app-unlock" -> appLocked.value = false
                                        "app-enable" -> security.setAppLockBiometricEnabled(true)
                                        "app-change" -> Unit
                                    }
                                }.exceptionOrNull()
                                if (error == null) onSuccess()
                                else {
                                    runCatching {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            error.message ?: "生物识别未完成，请重试",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    onUnavailable()
                                }
                            } finally {
                                biometricPromptActive = false
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        android.util.Log.e("ImprintsBio", "authError code=$errorCode msg=$errString target=$target")
                        biometricPromptActive = false
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            runCatching {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    errString.toString(),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        onUnavailable()
                    }
                })
            val promptTitle = when (target) {
                "feeling-unlock" -> "验证感受"
                "feeling-enable" -> "开启感受生物识别"
                "app-enable" -> "开启应用锁生物识别"
                else -> "解锁印迹"
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(promptTitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("使用 6 位口令")
                .build()
            android.util.Log.i("ImprintsBio", "authenticate target=$target withCrypto=${cipher != null}")
            biometricPromptActive = true
            try {
                if (cipher == null) prompt.authenticate(info)
                else prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            } catch (error: Exception) {
                biometricPromptActive = false
                android.util.Log.e("ImprintsBio", "authenticate threw", error)
                onUnavailable()
            }
        }
    }
}
