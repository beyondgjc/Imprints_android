package com.beyondguo.imprints.ui

/**
 * 时间滚轮的「中心行」算法（纯函数，可 JVM 单测）。
 *
 * 背景：`LazyColumn` + `rememberSnapFlingBehavior` 停稳后
 * `firstVisibleItemScrollOffset` **不一定为 0**（实测常停在近一格处）。
 * 此时只按 `firstVisibleItemIndex + 1` 取中心行会整体偏一行——
 * 表现就是「选 20 点，确认后显示 19 点」。
 *
 * 容器高度是 3 个格子，中心落在 1.5 格处：
 * - offset < 半格 → 中心在第 2 行（index + 1）
 * - offset ≥ 半格 → 中心在第 3 行（index + 2）
 */
internal object WheelPickerMath {
    fun centerIndex(firstVisible: Int, offsetPx: Int, itemHeightPx: Float): Int {
        if (itemHeightPx <= 0f) return firstVisible + 1
        return firstVisible + 1 + if (offsetPx >= itemHeightPx / 2f) 1 else 0
    }

    /** 把选中的中心行对齐到高亮格：firstVisible = 中心行 - 1。 */
    fun firstVisibleForCenter(centerIndex: Int): Int = centerIndex - 1
}
