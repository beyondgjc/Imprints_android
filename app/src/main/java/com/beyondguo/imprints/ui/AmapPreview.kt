package com.beyondguo.imprints.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions

/**
 * 高德地图预览：单点标注 + 手势缩放/平移。
 * 依赖 3dmap-location-search ≥11.2.000（MAP so 为 16KB 对齐）。
 * 坐标须为 GCJ-02；生命周期跟随页面 resume/pause/destroy。
 */
@Composable
fun AmapPreview(
    gcjLat: Double,
    gcjLng: Double,
    modifier: Modifier = Modifier,
    zoom: Float = 16f,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val target = remember(gcjLat, gcjLng) { LatLng(gcjLat, gcjLng) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            map?.apply {
                uiSettings.isZoomGesturesEnabled = true
                uiSettings.isScrollGesturesEnabled = true
                uiSettings.isZoomControlsEnabled = true
                uiSettings.isCompassEnabled = false
                uiSettings.isScaleControlsEnabled = true
                uiSettings.isMyLocationButtonEnabled = false
                uiSettings.isRotateGesturesEnabled = false
                uiSettings.isTiltGesturesEnabled = false
                clear()
                addMarker(
                    MarkerOptions()
                        .position(target)
                        .anchor(0.5f, 1f)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
                )
                moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
    )
}
