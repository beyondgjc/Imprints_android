package com.beyondguo.imprints.privacy

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.core.content.edit

/**
 * 防截屏 + 多任务隐藏（同一 FLAG_SECURE，系统层同时生效，故合并为一个开关）。
 * 默认开，对齐原正式包行为。
 */
object PrivacyPreferences {
    private const val PREFS = "imprints_privacy"
    private const val KEY_SECURE = "secure_window"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 开启后：禁止截屏/录屏，且多任务列表不显示页面内容 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SECURE, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_SECURE, value) }
    }

    fun apply(activity: Activity) {
        if (isEnabled(activity)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
