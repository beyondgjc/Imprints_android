package com.beyondguo.imprints.privacy

import android.content.Context
import androidx.core.content.edit

/**
 * 首次启动的隐私政策同意状态。
 *
 * 状态存 SharedPreferences，**不进系统云备份**（AndroidManifest 已 allowBackup="false"，
 * 换机后视为未同意，重新征求）。
 *
 * 关键约束：同意之前不得初始化任何会触碰用户数据的东西 —— MainActivity 靠
 * [consented] 门控，让 ImprintsApp 只渲染协议弹窗、不挂载 Room 与 Keystore。
 */
object ConsentState {
    private const val PREFS = "imprints_consent"
    private const val KEY_ACCEPTED = "policy_accepted"
    private const val KEY_VERSION = "policy_version"

    /** 政策版本号。内容有实质变更时 +1，老用户会在启动时重新征求。 */
    const val POLICY_VERSION = 1

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本机是否已对当前版本政策表示同意。 */
    fun isAccepted(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_ACCEPTED, false) && p.getInt(KEY_VERSION, 0) == POLICY_VERSION
    }

    fun setAccepted(context: Context, accepted: Boolean) = prefs(context).edit {
        putBoolean(KEY_ACCEPTED, accepted)
        putInt(KEY_VERSION, POLICY_VERSION)
    }

    /**
     * 读取隐私政策 / 用户协议正文。
     * 文案放在 assets 而非代码里：法务文本需要能独立修订，且要走商店审核的
     * 外部 URL 同步 —— 有单一事实来源才不会两处说法不一致。
     */
    fun readAsset(context: Context, name: String): String = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        "（协议文件读取失败：$name）"
    }
}
