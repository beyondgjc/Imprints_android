package com.beyondguo.imprints.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚轮中心行回归：吸附属停在非零 offset 时不能再偏一行。
 * 用例来自真机复现（MI 8，2026-10-19）：滑一格后高亮格是 20，旧算法报 19。
 */
class WheelPickerMathTest {
    private val h = 112f // 约 40.8dp @2.75x

    @Test
    fun offsetZeroUsesSecondRow() {
        assertEquals(5, WheelPickerMath.centerIndex(4, 0, h))
    }

    @Test
    fun halfItemOffsetSwitchesToThirdRow() {
        assertEquals(5, WheelPickerMath.centerIndex(4, (h / 2f).toInt() - 1, h))
        assertEquals(6, WheelPickerMath.centerIndex(4, (h / 2f).toInt(), h))
    }

    @Test
    fun restingNearFullItemStillReportsVisualCenter() {
        // 真机实测停稳点：offset 接近一格（0.95h），视觉中心是第 3 行
        assertEquals(6, WheelPickerMath.centerIndex(4, (h * 0.95f).toInt(), h))
    }

    @Test
    fun degenerateItemHeightFallsBack() {
        assertEquals(5, WheelPickerMath.centerIndex(4, 30, 0f))
    }

    @Test
    fun firstVisibleForCenterIsOneAbove() {
        assertEquals(5, WheelPickerMath.firstVisibleForCenter(6))
    }
}
