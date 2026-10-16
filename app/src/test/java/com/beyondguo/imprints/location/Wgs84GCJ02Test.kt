package com.beyondguo.imprints.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Wgs84GCJ02Test {

    @Test
    fun outOfChina_returnsUnchanged() {
        val (lat, lng) = Wgs84GCJ02.toGcj02(40.0, 10.0)
        assertEquals(40.0, lat, 0.0)
        assertEquals(10.0, lng, 0.0)
        assertTrue(Wgs84GCJ02.isOutOfChina(40.0, 10.0))
    }

    @Test
    fun beijing_shiftsWithinTensOfMeters() {
        // 天安门附近 WGS84 粗值
        val wgsLat = 39.9087
        val wgsLng = 116.3975
        val (gcjLat, gcjLng) = Wgs84GCJ02.toGcj02(wgsLat, wgsLng)
        // GCJ-02 相对 WGS84 通常偏移约几十到几百米（约 0.001~0.01 度）
        assertTrue(gcjLat != wgsLat || gcjLng != wgsLng)
        assertTrue(kotlin.math.abs(gcjLat - wgsLat) < 0.05)
        assertTrue(kotlin.math.abs(gcjLng - wgsLng) < 0.05)
        assertTrue(!Wgs84GCJ02.isOutOfChina(gcjLat, gcjLng))
    }

    /**
     * 回归：交给外部地图/导航的坐标必须归一到 GCJ-02。
     * 曾误转成 WGS-84，被高德再当 GCJ-02 用一遍，目的地偏西南 300~500 米。
     */
    @Test
    fun normalizeForExternalNavigation_alwaysYieldsGcj02() {
        // 已经是 GCJ-02：原样返回，不做任何反向转换
        val (sameLat, sameLng) = Wgs84GCJ02.toGcj02(39.90869, 116.3975, "GCJ-02")
        assertEquals(39.90869, sameLat, 0.0)
        assertEquals(116.3975, sameLng, 0.0)

        // 坐标系标注大小写不敏感
        val (lowerLat, _) = Wgs84GCJ02.toGcj02(39.90869, 116.3975, "gcj-02")
        assertEquals(39.90869, lowerLat, 0.0)

        // WGS-84 与「未知」都要转到 GCJ-02：数值向东北变大，且两条路径结果一致
        val (convertedLat, convertedLng) = Wgs84GCJ02.toGcj02(39.90869, 116.3975, "WGS-84")
        assertTrue(convertedLat > 39.90869)
        assertTrue(convertedLng > 116.3975)
        val (nullLat, nullLng) = Wgs84GCJ02.toGcj02(39.90869, 116.3975, null)
        assertEquals(convertedLat, nullLat, 0.0)
        assertEquals(convertedLng, nullLng, 0.0)
    }
}
