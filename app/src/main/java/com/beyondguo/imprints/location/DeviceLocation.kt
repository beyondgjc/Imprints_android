package com.beyondguo.imprints.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设备定位。
 * - 优先返回 last known（附近列表先出结果，避免空等「定位超时」）
 * - 无 last known 时等待新鲜定位：API 30+ getCurrentLocation；API 29 多 provider requestLocationUpdates
 * - 超时回落 last known
 */
object DeviceLocation {

    /** 返回 WGS-84；无权限/全失败返回 null。 */
    @SuppressLint("MissingPermission")
    suspend fun fetchWgs84(context: Context, timeoutMs: Long = 12_000): Pair<Double, Double>? {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.allProviders.filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null

        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (last != null) {
            // 有缓存：最多再等 2s 取新鲜点，否则直接用缓存
            val fresher = withTimeoutOrNull(2_000) {
                awaitFresh(app, manager, providers, last, timeoutMs)
            }
            return fresher ?: (last.latitude to last.longitude)
        }

        return withTimeoutOrNull(timeoutMs) {
            awaitFresh(app, manager, providers, null, timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFresh(
        context: Context,
        manager: LocationManager,
        providers: List<String>,
        fallback: Location?,
        timeoutMs: Long,
    ): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        var settled = false
        val handler = Handler(Looper.getMainLooper())
        val listeners = mutableListOf<LocationListener>()

        fun finish(value: Pair<Double, Double>?) {
            if (settled) return
            settled = true
            listeners.forEach { l -> runCatching { manager.removeUpdates(l) } }
            handler.removeCallbacksAndMessages(null)
            if (cont.isActive) cont.resume(value)
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancellation = android.os.CancellationSignal()
                val preferred = providers.firstOrNull {
                    it == LocationManager.NETWORK_PROVIDER || it == LocationManager.GPS_PROVIDER
                } ?: providers.first()
                manager.getCurrentLocation(preferred, cancellation, context.mainExecutor) { loc ->
                    if (loc != null) finish(loc.latitude to loc.longitude)
                    else finish(fallback?.let { it.latitude to it.longitude })
                }
                handler.postDelayed(
                    { finish(fallback?.let { it.latitude to it.longitude }) },
                    minOf(timeoutMs, 10_000L),
                )
                cont.invokeOnCancellation {
                    cancellation.cancel()
                    handler.removeCallbacksAndMessages(null)
                }
            } else {
                providers.forEach { name ->
                    val single = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            finish(location.latitude to location.longitude)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    listeners += single
                    manager.requestLocationUpdates(name, 0L, 0f, single, Looper.getMainLooper())
                }
                handler.postDelayed(
                    { finish(fallback?.let { it.latitude to it.longitude }) },
                    minOf(timeoutMs, 10_000L),
                )
                cont.invokeOnCancellation {
                    settled = true
                    listeners.forEach { l -> runCatching { manager.removeUpdates(l) } }
                    handler.removeCallbacksAndMessages(null)
                }
            }
        } catch (_: SecurityException) {
            finish(null)
        }
    }

    /** WGS-84 直线距离（米）。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toInt()
    }
}
