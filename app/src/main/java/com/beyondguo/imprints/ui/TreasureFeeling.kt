package com.beyondguo.imprints.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlin.time.Duration.Companion.milliseconds

private val Gold = Color(0xFFF2CE7B)
private val DeepGreen = Color(0xFF06A854)
private val SoftSoil = Color(0xFFF3EDE3)
private val MapInk = Color(0xFF8B7355)

/**
 * 「感受」编辑区 — 方案 A + 旋转箭头：
 * 始终同一 Meta 行结构；→ 收起，点按顺时针 90° 成 ↓ 并展开，再点收回。
 */
@Composable
fun TreasureFeelingSection(
    feeling: String,
    onFeelingChange: (String) -> Unit,
    feelingLocked: Boolean,
    onUnlock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(feeling.isNotBlank()) }
    var lockedExpanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val hasText = feeling.isNotBlank()
    val bringIntoView = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 等 IME inset 稳定后再滚动；只在获得焦点时滚动会早于键盘完成展开。
    LaunchedEffect(focused, expanded, imeBottomPx, feeling.length) {
        if (focused && expanded && imeBottomPx > 0) {
            kotlinx.coroutines.delay(32.milliseconds)
            runCatching { bringIntoView.bringIntoView() }
        }
    }

    if (feelingLocked) {
        EditorMetaRow(
            leadingContent = { TreasureGem(Modifier.size(20.dp)) },
            leadingTint = Color(0xFFF7F4EC),
            title = "感受",
            subtitle = "加密已开启 · 验证后才能填写",
            titleColor = Ink,
            onClick = { lockedExpanded = !lockedExpanded },
            trailing = EditorMetaTrailing.Expand,
            expanded = lockedExpanded,
        )
        if (lockedExpanded) {
            Spacer(Modifier.height(6.dp))
            TreasureLockedCard(onUnlock = onUnlock)
        }
        return
    }

    val subtitle = when {
        !expanded && hasText -> feeling.replace('\n', ' ').take(20) + if (feeling.length > 20) "…" else ""
        !expanded -> "可选 · 保存后刮开可见"
        hasText -> null
        else -> "写下那一刻的感受"
    }

    Column(Modifier.fillMaxWidth()) {
        EditorMetaRow(
            leadingContent = { TreasureGem(Modifier.size(20.dp)) },
            leadingTint = Color(0xFFF7F4EC),
            title = "感受",
            subtitle = subtitle,
            titleColor = if (expanded) Ink else if (hasText) Ink else Quiet,
            onClick = { expanded = !expanded },
            trailing = EditorMetaTrailing.Expand,
            expanded = expanded,
        )

        if (!expanded) return@Column

        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .clip(RoundedCornerShape(14.dp))
                .background(if (hasText) Color(0xFFFFFDF8) else SoftSoil)
                .border(
                    1.dp,
                    if (focused) Green else Color(0xFFE5DCCB),
                    RoundedCornerShape(14.dp),
                ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = feeling,
                    onValueChange = onFeelingChange,
                    placeholder = {
                        Text(
                            "那一刻，你有什么感受？",
                            color = MapInk.copy(alpha = 0.65f),
                            fontSize = 15.sp,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            focused = it.isFocused
                        },
                    minLines = 2,
                    maxLines = 8,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        color = Ink,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = DeepGreen,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TreasureEmptyHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 迷你 X 宝藏标记
        Canvas(Modifier.size(22.dp)) {
            val c = size.minDimension / 2f
            val r = size.minDimension * 0.28f
            drawLine(
                Gold, Offset(c - r, c - r), Offset(c + r, c + r),
                strokeWidth = 3f, cap = StrokeCap.Round,
            )
            drawLine(
                Gold, Offset(c + r, c - r), Offset(c - r, c + r),
                strokeWidth = 3f, cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("此刻的心情，是一份小宝藏", color = MapInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("写下它，和这条印迹一起封存", color = Quiet, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SealedPreviewChip() {
    // 迷你绿涂层预告：与浏览端刮刮乐呼应
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Green.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Green),
        )
        Spacer(Modifier.width(6.dp))
        Text("保存后盖绿涂层 · 刮开可见", color = DeepGreen, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TreasureLockedCard(onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SoftSoil)
            .border(1.5.dp, Color(0xFFD9CBB3), RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 简易锁 + 宝石
                Canvas(Modifier.size(28.dp)) {
                    val w = size.width
                    val h = size.height
                    // 锁体
                    drawRoundRect(
                        color = Green,
                        topLeft = Offset(w * 0.22f, h * 0.48f),
                        size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.38f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                    // 锁梁
                    drawArc(
                        color = Green,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.30f, h * 0.18f),
                        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.40f),
                        style = Stroke(width = 4f),
                    )
                    // 宝石点缀
                    drawCircle(Gold, radius = 3f, center = Offset(w * 0.5f, h * 0.67f))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("宝藏箱已上锁", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("感受加密开启中，先验证才能继续埋", color = Quiet, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onUnlock) {
                Text("解锁后填写感受", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 藏宝图四角：虚线边框感 + 四角星 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashedCorners() {
    val w = size.width
    val h = size.height
    val inset = 10.dp.toPx()
    val dash = 6.dp.toPx()
    val gap = 5.dp.toPx()
    val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
    val color = Gold.copy(alpha = 0.7f)

    fun dashedLine(from: Offset, to: Offset) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        var t = 0f
        while (t < len) {
            val end = (t + dash).coerceAtMost(len)
            drawLine(
                color,
                Offset(from.x + ux * t, from.y + uy * t),
                Offset(from.x + ux * end, from.y + uy * end),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            t = end + gap
        }
    }

    // 四边内缩虚线
    dashedLine(Offset(inset, inset), Offset(w - inset, inset))
    dashedLine(Offset(w - inset, inset), Offset(w - inset, h - inset))
    dashedLine(Offset(w - inset, h - inset), Offset(inset, h - inset))
    dashedLine(Offset(inset, h - inset), Offset(inset, inset))

    // 四角小星
    fun star(cx: Float, cy: Float, r: Float) {
        val path = Path().apply {
            moveTo(cx, cy - r)
            quadraticBezierTo(cx, cy, cx + r, cy)
            quadraticBezierTo(cx, cy, cx, cy + r)
            quadraticBezierTo(cx, cy, cx - r, cy)
            quadraticBezierTo(cx, cy, cx, cy - r)
            close()
        }
        drawPath(path, color)
    }
    val sr = 4.5.dp.toPx()
    star(inset + 2.dp.toPx(), inset + 2.dp.toPx(), sr)
    star(w - inset - 2.dp.toPx(), inset + 2.dp.toPx(), sr)
    star(w - inset - 2.dp.toPx(), h - inset - 2.dp.toPx(), sr)
    star(inset + 2.dp.toPx(), h - inset - 2.dp.toPx(), sr)
}

/** 标题旁呼吸感小宝石 */
@Composable
private fun TreasureGem(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "gem")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "gem-pulse",
    )
    Canvas(modifier) {
        val c = size.minDimension / 2f
        val r = c * pulse
        // 菱形宝石
        val path = Path().apply {
            moveTo(c, c - r)
            lineTo(c + r * 0.75f, c)
            lineTo(c, c + r)
            lineTo(c - r * 0.75f, c)
            close()
        }
        drawPath(path, Gold)
        drawPath(path, DeepGreen.copy(alpha = 0.35f), style = Stroke(width = 1.5f))
    }
}
