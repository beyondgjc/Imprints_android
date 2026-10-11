package com.beyondguo.imprints.ui

/**
 * 调试钩子的宿主侧桥接：主源集只认这个接口，具体实现由 debug 源集注入。
 *
 * release 构建里 [provider] 始终为 null，所以正式包既不注册广播、也没有任何
 * 可被外部触发的预填入口。
 */
object DebugSeedBridge {
    /** 由 debug 源集在启动时设置；返回 null 表示没有预填值。 */
    @Volatile
    var provider: (() -> String?)? = null

    /**
     * 每次广播递增，用于让消费方的 LaunchedEffect 重新触发。
     * 单靠 `LaunchedEffect(Unit)` 只在首次进入页面时读一次，反复进出会失效。
     */
    @Volatile
    var revision: Int = 0

    fun prefillSearch(): String? = provider?.invoke()
}
