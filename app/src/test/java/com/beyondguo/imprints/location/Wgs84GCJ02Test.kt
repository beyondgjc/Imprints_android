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
}
