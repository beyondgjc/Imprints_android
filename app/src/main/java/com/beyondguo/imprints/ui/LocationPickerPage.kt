package com.beyondguo.imprints.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.imprints.data.PlaceEntity
import com.beyondguo.imprints.location.AmapPoiService
import com.beyondguo.imprints.location.DeviceLocation
import com.beyondguo.imprints.location.PoiResultItem
import com.beyondguo.imprints.location.Wgs84GCJ02
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 微信风格「所在位置」选择页：
 * 顶栏返回/标题/搜索；首项「不显示位置」；附近列表（距离 | 地址）；搜索模式。
 * 仅在用户进入本页并授权后才请求定位。
 */
@Composable
fun LocationPickerPage(
    selected: PlaceEntity?,
    onSelect: (PlaceEntity?) -> Unit,
    onBack: () -> Unit,
    report: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PoiResultItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<PoiResultItem>>(emptyList()) }
    var nearbyLoading by remember { mutableStateOf(false) }
    var nearbyHint by remember { mutableStateOf("") }
    var cityLabel by remember { mutableStateOf("") }
    var lastWgs by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    BackHandler { onBack() }

    fun applyPoi(item: PoiResultItem) {
        onSelect(
            PlaceEntity(
                UUID.randomUUID().toString(),
                item.name,
                item.address,
                item.latitude,
                item.longitude,
                "GCJ-02",
                "AMAP",
                item.poiId.ifEmpty { null },
            )
        )
    }

    suspend fun loadNearby() {
        nearbyLoading = true
        nearbyHint = "正在定位…"
        val wgs = DeviceLocation.fetchWgs84(context, timeoutMs = 12_000)
        if (wgs == null) {
            nearbyLoading = false
            nearbyHint = "定位失败或超时，可点右上角搜索地点"
            return
        }
        lastWgs = wgs
        val (gcjLat, gcjLng) = Wgs84GCJ02.toGcj02(wgs.first, wgs.second)
        nearbyHint = "正在查找附近地点…"
        runCatching {
            kotlinx.coroutines.withTimeout(8_000) {
                AmapPoiService.searchNearby(context, gcjLat, gcjLng)
            }
        }.onSuccess { list ->
            nearby = list
            nearbyHint = if (list.isEmpty()) "附近暂无结果，可搜索地点" else ""
            // 用第一条粗略城市（地址首段）作分区标题
            cityLabel = list.firstOrNull()?.address
                ?.substringBefore("省")
                ?.substringBefore("市")
                ?.substringBefore("自治区")
                ?.substringBefore("特别行政区")
                ?.substringAfterLast("、")
                ?.substringAfterLast("/")
                ?.trim()
                ?.takeIf { it.length in 2..10 }
                ?: ""
        }.onFailure { error ->
            nearby = emptyList()
            nearbyHint = "附近地点暂不可用，可搜索地点"
            if (error !is kotlinx.coroutines.TimeoutCancellationException) report(error)
        }
        nearbyLoading = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            scope.launch { loadNearby() }
        } else {
            nearbyHint = "未授权定位，可搜索地点或返回"
        }
    }

    // 进入页面：已有权限则自动拉附近；否则引导授权（微信式，不静默弹窗）
    LaunchedEffect(Unit) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            loadNearby()
        } else {
            nearbyHint = "点击允许定位，查看附近地点"
        }
    }

    // 搜索防抖
    LaunchedEffect(query, searchMode) {
        if (!searchMode) return@LaunchedEffect
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        if (query.trim() != q) return@LaunchedEffect
        searching = true
        searchJob = scope.launch {
            runCatching {
                kotlinx.coroutines.withTimeout(8_000) { AmapPoiService.searchKeyword(context, q) }
            }.onSuccess { list ->
                if (query.trim() == q) searchResults = list
            }.onFailure { error ->
                if (query.trim() == q) {
                    searchResults = emptyList()
                    report(error)
                }
            }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F7F7))) {
        // 顶栏：返回 | 标题 | 搜索
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (searchMode) {
                    searchMode = false
                    query = ""
                    searchResults = emptyList()
                } else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Ink)
            }
            Text(
                "所在位置",
                Modifier.weight(1f),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { searchMode = !searchMode }) {
                if (searchMode) {
                    Icon(Icons.Default.Close, "关闭搜索", tint = Ink)
                } else {
                    Icon(Icons.Default.Search, "搜索地点", tint = Ink)
                }
            }
        }

        if (searchMode) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("搜索地点", color = Quiet) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Quiet, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Line,
                    focusedBorderColor = Green,
                ),
            )
            if (searching) {
                Text("搜索中…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Quiet, fontSize = 13.sp)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (searchResults.isEmpty() && !searching && query.isNotBlank()) {
                    item {
                        Text(
                            "未找到相关地点",
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            color = Quiet,
                            fontSize = 14.sp,
                        )
                    }
                }
                items(searchResults, key = { it.poiId + it.name }) { item ->
                    PoiRow(item, selected = false) { applyPoi(item) }
                    FineDivider()
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // 不显示位置
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelect(null) }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "不显示位置",
                            Modifier.weight(1f),
                            color = if (selected == null) Green else Ink,
                            fontSize = 16.sp,
                        )
                        if (selected == null) {
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
                        }
                    }
                    FineDivider()
                }

                // 当前已选（非空且来自搜索/手动）
                if (selected != null && selected.provider != null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { /* 已选中，无操作 */ }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.LocationOn, null, tint = Green, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(selected.name, Modifier.weight(1f), color = Ink, fontSize = 16.sp)
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
                        }
                        FineDivider()
                    }
                }

                if (selected != null && selected.name.isNotBlank()) {
                    item {
                        Text(
                            "清除已选择的位置",
                            Modifier.fillMaxWidth()
                                .clickable { onSelect(null) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            color = Quiet,
                            fontSize = 13.sp,
                        )
                        FineDivider()
                    }
                }

                // 未授权 → 引导
                if (nearbyHint.startsWith("点击允许")) {
                    item {
                        TextButton(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        ) {
                            Text("允许定位，查看附近地点", color = Green, fontSize = 15.sp)
                        }
                        Text(
                            "仅在本次选择时请求定位权限",
                            Modifier.padding(horizontal = 20.dp),
                            color = Quiet,
                            fontSize = 12.sp,
                        )
                    }
                }

                // 附近加载中
                if (nearbyLoading) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Green, strokeWidth = 2.dp)
                            Text(nearbyHint, color = Quiet, fontSize = 14.sp)
                        }
                    }
                } else if (nearbyHint.isNotBlank() && nearby.isEmpty()) {
                    item {
                        Text(
                            nearbyHint,
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            color = Quiet,
                            fontSize = 14.sp,
                        )
                        FineDivider()
                    }
                }

                // 城市分区
                if (nearby.isNotEmpty()) {
                    item {
                        Text(
                            cityLabel.ifBlank { "附近地点" },
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    items(nearby, key = { it.poiId + it.name }) { item ->
                        PoiRow(item, selected = selected?.providerPoiId != null && selected.providerPoiId == item.poiId) {
                            applyPoi(item)
                        }
                        FineDivider()
                    }
                }

                item {
                    Text(
                        "位置为可选字段，可随时更换或移除",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        color = Quiet,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PoiRow(item: PoiResultItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            val meta = buildString {
                item.distanceMeters?.let { d ->
                    append(if (d < 1000) "${d}m内" else "%.1fkm".format(d / 1000.0))
                    if (item.address.isNotBlank()) append(" | ")
                }
                append(item.address)
            }
            if (meta.isNotBlank()) {
                Text(meta, color = Quiet, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
        }
    }
}
