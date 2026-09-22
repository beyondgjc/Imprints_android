package com.beyondguo.imprints.ui

/**
 * 时间线 LazyColumn 的 item 索引映射。
 * 结构约定：0=页头；其后每个月分组为「吸顶月份（含日历入口）+ 若干条目」。
 * 传入按展示顺序排列的 (entryId, month) 列表，month 取 eventLocalMinute 前 7 位。
 */
internal fun buildTimelineIndexes(entries: List<Pair<String, String>>): Map<String, Int> {
    val indexes = mutableMapOf<String, Int>()
    var index = 1
    var currentMonth = ""
    for ((id, month) in entries) {
        if (month != currentMonth) {
            index++ // stickyHeader
            currentMonth = month
        }
        indexes[id] = index++
    }
    return indexes
}
