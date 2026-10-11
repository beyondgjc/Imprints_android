package com.beyondguo.imprints.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * **仅调试用**的测试钩子：让 adb 能把中文字符串带进 UI。
 *
 * 背景：`adb shell input text` 只支持 ASCII，中文会被发成字面 `\uXXXX`；
 * 设备上也没有可注入 Unicode 的输入法。为了给官网采集中文界面的截图、
 * 以及手工验收中文输入路径，这里用一个仅 debug 生效的广播接收器接收预填文本。
 *
 * 用法：
 *   adb shell am broadcast -a com.beyondguo.imprints.DEBUG_PREFILL --es search "窗台"
 *   （随后进搜索页，输入框会自动带上该关键词）
 *
 * 由 [DebugSeedProvider] 自启动注册；正式构建里没有这个类，也不会有任何入口。
 */
object DebugSeed {
    const val ACTION_PREFILL = "com.beyondguo.imprints.DEBUG_PREFILL"

    /** 搜索页要预填的关键词；null 表示不预填。 */
    @Volatile
    var prefillSearch: String? = null
        private set

    private var registered = false

    fun install(context: Context) {
        DebugSeedBridge.provider = { prefillSearch }
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                prefillSearch = intent?.getStringExtra("search")
            }
        }
        val filter = IntentFilter(ACTION_PREFILL)
        val appContext = context.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // 只给 debug 用且不对外暴露；adb/shell 发出的广播仍可送达
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
