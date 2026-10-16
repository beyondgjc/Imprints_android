package com.beyondguo.imprints.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondguo.imprints.data.EntryEntity
import com.beyondguo.imprints.data.PlaceEntity
import com.beyondguo.imprints.location.Wgs84GCJ02
import com.beyondguo.imprints.R
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri

/**
 * 位置详情页（设计 49/50）：
 * 静态示意地图 + 地点名/地址 + 导航去此处 + 相关印迹。
 */
@Composable
fun LocationDetailPage(
    place: PlaceEntity,
    relatedEntries: List<EntryEntity>,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val context = LocalContext.current
    // Toast 在 onClick lambda 里弹（捕获），文案提前取一次避免在回调里查资源
    val noMapAppHint = stringResource(R.string.err_no_map_app)
    BackHandler { onBack() }
    // 交给外部地图/导航的坐标：国内 App 按 GCJ-02 解析 geo:，所以统一归一到 GCJ-02。
    // 曾误按 RFC 5870 转成 WGS-84，被高德再当 GCJ-02 用一遍，目的地偏西南 300~500 米。
    val coordinates = if (place.latitude != null && place.longitude != null) {
        val (gcjLat, gcjLng) = Wgs84GCJ02.toGcj02(
            place.latitude, place.longitude, place.coordinateSystem)
        "$gcjLat,$gcjLng"
    } else null

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            // 有坐标：嵌高德真地图（可缩放平移）；无坐标：静态示意 + 点按交给外部地图
            // 高德地图控件要 GCJ-02：与导航入口用同一套归一逻辑，避免两处走反
            val latLng = if (place.latitude != null && place.longitude != null) {
                Wgs84GCJ02.toGcj02(place.latitude, place.longitude, place.coordinateSystem)
            } else null
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF0F3F1))
                    .clickable(enabled = latLng == null && coordinates != null) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                "geo:0,0?q=$coordinates".toUri()))
                        }.onFailure {
                            Toast.makeText(context, noMapAppHint, Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (latLng != null) {
                    AmapPreview(
                        gcjLat = latLng.first,
                        gcjLng = latLng.second,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        val road = Color(0xFFDFE5DF)
                        drawLine(road, Offset(0f, size.height * 0.35f), Offset(size.width, size.height * 0.28f), 18.dp.toPx(), StrokeCap.Round)
                        drawLine(road, Offset(0f, size.height * 0.72f), Offset(size.width, size.height * 0.65f), 18.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width * 0.28f, 0f), Offset(size.width * 0.28f, size.height), 7.dp.toPx())
                        drawLine(Color.White, Offset(size.width * 0.72f, 0f), Offset(size.width * 0.72f, size.height), 7.dp.toPx())
                        drawLine(Color.White, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 7.dp.toPx())
                        val pin = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Green.copy(alpha = 0.14f), 30.dp.toPx(), pin)
                        drawCircle(Green, 8.dp.toPx(), pin)
                    }
                    Icon(
                        Icons.Default.LocationOn,
                        null,
                        tint = Green,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(place.name, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (place.address.isNotBlank()) {
                    Text(place.address, color = Quiet, fontSize = 13.sp)
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (coordinates == null) {
                        // 无坐标时按名称搜索
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                "geo:0,0?q=${Uri.encode(place.name)}".toUri()))
                        }.onFailure {
                            Toast.makeText(context, noMapAppHint, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                "geo:0,0?q=$coordinates".toUri()))
                        }.onFailure {
                            Toast.makeText(context, noMapAppHint, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.location_navigate)) }
            Text(stringResource(R.string.location_navigate_sub), color = Quiet, fontSize = 12.sp)
        }
        item {
            Text(stringResource(R.string.location_related), color = Quiet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (relatedEntries.isEmpty()) {
            item {
                Text(stringResource(R.string.location_empty), color = Ink, fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
        } else {
            items(relatedEntries, key = { it.id }) { entry ->
                OutlinePanel(Modifier.fillMaxWidth().clickable { onOpenEntry(entry.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.eventLocalMinute.replace('T', ' '), color = Quiet, fontSize = 12.sp)
                        Text(entry.body.ifBlank { stringResource(R.string.entry_media_only) }, color = Ink, fontSize = 15.sp,
                            maxLines = 3, lineHeight = 22.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
