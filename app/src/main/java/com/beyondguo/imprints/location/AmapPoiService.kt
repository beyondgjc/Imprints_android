package com.beyondguo.imprints.location

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 高德 POI 搜索结果的 UI 模型（坐标系固定为 GCJ-02）。 */
data class PoiResultItem(
    val poiId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    /** 与检索中心的近似距离（米），仅附近检索填充。 */
    val distanceMeters: Int? = null,
)

/**
 * 高德 POI 搜索封装：关键字检索 + 周边检索。
 * 合规：构造 PoiSearch 前必须完成 ServiceSettings.updatePrivacyShow/Agree；
 * Key 由 Manifest meta-data 注入。
 */
object AmapPoiService {

    @Volatile
    private var privacyReady = false

    /** 在用户主动使用位置搜索/附近前调用一次（应用隐私政策需已包含高德条款）。 */
    fun ensurePrivacyAccepted(context: Context) {
        if (privacyReady) return
        synchronized(this) {
            if (privacyReady) return
            ServiceSettings.updatePrivacyShow(context.applicationContext, true, true)
            ServiceSettings.updatePrivacyAgree(context.applicationContext, true)
            privacyReady = true
        }
    }

    /** 关键字检索；city 为空表示全国。 */
    suspend fun searchKeyword(
        context: Context,
        keyword: String,
        city: String = "",
    ): List<PoiResultItem> {
        ensurePrivacyAccepted(context)
        val query = PoiSearch.Query(keyword, "", city).apply {
            pageSize = 10
            pageNum = 1
        }
        return suspendCancellableCoroutine { cont ->
            var search: PoiSearch? = null
            try {
                search = PoiSearch(context.applicationContext, query).apply {
                    setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                            if (cont.isActive) cont.resume(parseResult(result, rCode))
                        }

                        override fun onPoiItemSearched(item: PoiItem?, rCode: Int) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                    searchPOIAsyn()
                }
            } catch (e: AMapException) {
                if (cont.isActive) cont.resume(emptyList())
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    /**
     * 周边检索。[centerLat]/[centerLng] 必须是 GCJ-02；
     * 设备 WGS84 坐标请先经 [Wgs84GCJ02.toGcj02] 转换。
     */
    suspend fun searchNearby(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int = 2000,
    ): List<PoiResultItem> {
        ensurePrivacyAccepted(context)
        val query = PoiSearch.Query("", "", "").apply {
            pageSize = 20
            pageNum = 1
        }
        return suspendCancellableCoroutine { cont ->
            try {
                PoiSearch(context.applicationContext, query).apply {
                    setBound(PoiSearch.SearchBound(LatLonPoint(centerLat, centerLng), radiusMeters))
                    setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                            if (cont.isActive) {
                                cont.resume(parseResult(result, rCode).map { item ->
                                    // GCJ-02 平面近似即可展示距离
                                    val d = kotlin.math.sqrt(
                                        (item.latitude - centerLat).let { it * it } +
                                            (item.longitude - centerLng).let { it * it }
                                    )
                                    // 粗算：1 度约 111km，够列表展示
                                    val meters = (d * 111_000).toInt()
                                    item.copy(distanceMeters = meters)
                                }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE })
                            }
                        }

                        override fun onPoiItemSearched(item: PoiItem?, rCode: Int) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                    searchPOIAsyn()
                }
            } catch (e: AMapException) {
                if (cont.isActive) cont.resume(emptyList())
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    private fun parseResult(result: PoiResult?, rCode: Int): List<PoiResultItem> {
        if (rCode != AMapException.CODE_AMAP_SUCCESS) return emptyList()
        val pois = result?.pois ?: return emptyList()
        return pois.mapNotNull { item ->
            val point = item.latLonPoint ?: return@mapNotNull null
            val name = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PoiResultItem(
                poiId = item.poiId.orEmpty(),
                name = name,
                address = item.snippet.orEmpty(),
                latitude = point.latitude,
                longitude = point.longitude,
            )
        }
    }
}
