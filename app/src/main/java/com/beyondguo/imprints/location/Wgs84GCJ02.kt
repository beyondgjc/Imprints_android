package com.beyondguo.imprints.location

import kotlin.math.abs

/**
 * WGS-84（Android LocationManager）与 GCJ-02（高德 POI）互转。
 * 仅中国大陆范围适用；境外坐标原样返回。
 * 两套坐标不可混存：places.coordinateSystem 必须如实标注。
 */
object Wgs84GCJ02 {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun isOutOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun toGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
        if (isOutOfChina(wgsLat, wgsLng)) return wgsLat to wgsLng
        var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        var dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return wgsLat + dLat to wgsLng + dLng
    }

    /** 近似逆变换：GCJ-02 → WGS-84，供外部 geo: 导航使用。 */
    fun toWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (isOutOfChina(gcjLat, gcjLng)) return gcjLat to gcjLng
        val (mgLat, mgLng) = toGcj02(gcjLat, gcjLng)
        return gcjLat * 2 - mgLat to gcjLng * 2 - mgLng
    }

    /**
     * 按 [coordinateSystem] 归一到 WGS-84（Android geo: / 系统地图惯例）。
     * null/未知按 WGS84 处理（手填无坐标不会走到这里）。
     */
    fun toWgs84(lat: Double, lng: Double, coordinateSystem: String?): Pair<Double, Double> =
        if (coordinateSystem.equals("GCJ-02", ignoreCase = true)) toWgs84(lat, lng)
        else lat to lng

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * abs(x)
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * abs(x)
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun sin(x: Double): Double = kotlin.math.sin(x)
    private fun cos(x: Double): Double = kotlin.math.cos(x)
}
