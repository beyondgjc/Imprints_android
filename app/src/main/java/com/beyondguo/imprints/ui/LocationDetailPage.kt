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
    BackHandler { onBack() }
    val coordinates = if (place.latitude != null && place.longitude != null) {
        val (wgsLat, wgsLng) = Wgs84GCJ02.toWgs84(
            place.latitude!!, place.longitude!!, place.coordinateSystem)
        "$wgsLat,$wgsLng"
    } else null

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            // 静意地图占位（无 Key 时不嵌在线地图，可点击交给外部地图）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF0F3F1))
                    .clickable(enabled = coordinates != null) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$coordinates")))
                        }.onFailure {
                            Toast.makeText(context, "未找到可用的地图应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
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
                                Uri.parse("geo:0,0?q=${Uri.encode(place.name)}")))
                        }.onFailure {
                            Toast.makeText(context, "未找到可用的地图应用", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$coordinates")))
                        }.onFailure {
                            Toast.makeText(context, "未找到可用的地图应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("导航去此处") }
            Text("导航将交给已安装的地图应用", color = Quiet, fontSize = 12.sp)
        }
        item {
            Text("相关印迹", color = Quiet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (relatedEntries.isEmpty()) {
            item {
                Text("这个位置还没有印迹", color = Ink, fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
        } else {
            items(relatedEntries, key = { it.id }) { entry ->
                OutlinePanel(Modifier.fillMaxWidth().clickable { onOpenEntry(entry.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.eventLocalMinute.replace('T', ' '), color = Quiet, fontSize = 12.sp)
                        Text(entry.body.ifBlank { "媒体印迹" }, color = Ink, fontSize = 15.sp,
                            maxLines = 3, lineHeight = 22.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
