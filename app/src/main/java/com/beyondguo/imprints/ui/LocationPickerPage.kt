package com.beyondguo.imprints.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource
import kotlin.time.Duration.Companion.milliseconds

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

    // 这些文案在 Composable 主体里取一次，供下面的 suspend fun loadNearby() 闭包捕获。
    // 不用 context.getString：LocalContext.current 会随重组变化，在长耗时 suspend 函数里
    // 持有它查资源有拿到过期 Context 的风险；stringResource 走的是当前 composition 的资源。
    val hintLocating = stringResource(R.string.location_locating)
    val hintLocateFailed = stringResource(R.string.location_locate_failed)
    val hintSearching = stringResource(R.string.location_nearby_searching)
    val hintEmpty = stringResource(R.string.location_nearby_empty)
    val hintUnavailable = stringResource(R.string.location_nearby_unavailable)

    val hintNotGranted = stringResource(R.string.location_not_granted)
    val hintTapAllow = stringResource(R.string.location_tap_allow)
    val hintTapAllowShort = stringResource(R.string.location_tap_allow_short)

    suspend fun loadNearby() {
        nearbyLoading = true
        nearbyHint = hintLocating
        val wgs = DeviceLocation.fetchWgs84(context, timeoutMs = 12_000)
        if (wgs == null) {
            nearbyLoading = false
            nearbyHint = hintLocateFailed
            return
        }
        lastWgs = wgs
        val (gcjLat, gcjLng) = Wgs84GCJ02.toGcj02(wgs.first, wgs.second)
        nearbyHint = hintSearching
        runCatching {
            kotlinx.coroutines.withTimeout(8_000.milliseconds) {
                AmapPoiService.searchNearby(context, gcjLat, gcjLng)
            }
        }.onSuccess { list ->
            nearby = list
            nearbyHint = if (list.isEmpty()) hintEmpty else ""
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
            nearbyHint = hintUnavailable
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
            nearbyHint = hintNotGranted
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
            nearbyHint = hintTapAllow
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
        delay(350.milliseconds)
        if (query.trim() != q) return@LaunchedEffect
        searching = true
        searchJob = scope.launch {
            runCatching {
                kotlinx.coroutines.withTimeout(8_000.milliseconds) { AmapPoiService.searchKeyword(context, q) }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Ink)
            }
            Text(
                stringResource(R.string.editor_place_label),
                Modifier.weight(1f),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { searchMode = !searchMode }) {
                if (searchMode) {
                    Icon(Icons.Default.Close, stringResource(R.string.a11y_close_search), tint = Ink)
                } else {
                    Icon(Icons.Default.Search, stringResource(R.string.location_search_hint), tint = Ink)
                }
            }
        }

        if (searchMode) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text(stringResource(R.string.location_search_hint), color = Quiet) },
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
                Text(stringResource(R.string.location_searching), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Quiet, fontSize = 13.sp)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (searchResults.isEmpty() && !searching && query.isNotBlank()) {
                    item {
                        Text(
                            stringResource(R.string.location_not_found),
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
                            stringResource(R.string.location_none),
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
                            stringResource(R.string.location_clear),
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
                if (nearbyHint.startsWith(hintTapAllowShort)) {
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
                            Text(stringResource(R.string.location_grant), color = Green, fontSize = 15.sp)
                        }
                        Text(
                            stringResource(R.string.location_grant_sub),
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
                            cityLabel.ifBlank { stringResource(R.string.location_nearby_title) },
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
                        stringResource(R.string.location_optional_hint),
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
                    append(
                        if (d < 1000) {
                            stringResource(R.string.format_distance_m, d)
                        } else {
                            stringResource(R.string.format_distance_km, "%.1f".format(d / 1000.0))
                        }
                    )
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
