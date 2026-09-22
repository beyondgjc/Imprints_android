package com.beyondguo.imprints.security

/** 6 位数字口令与失败限速的纯策略，供 ImprintSecurity 使用并可在 JVM 上单测。 */
object PinPolicy {
    private val PIN = Regex("[0-9]{6}")

    fun isValid(pin: String): Boolean = pin.matches(PIN)

    fun requireValid(pin: String) = require(isValid(pin)) { "请输入 6 位数字口令" }

    /** 第 5 次失败起开始退避：每次 +5s，上限 300s；未达阈值返回 0。 */
    fun blockedUntil(attempts: Int, nowMs: Long): Long =
        if (attempts < 5) 0 else nowMs + minOf(300_000L, 5_000L * (attempts - 4).toLong())
}
