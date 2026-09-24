package com.beyondguo.imprints.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF242830)
internal val Quiet = Color(0xFF858D9B)
internal val Line = Color(0xFFE8E7E3)
internal val Green = Color(0xFF07B85D)
internal val PaleGreen = Color(0xFFE7F9EF)
/** 分组卡片底（对齐 Penly PenBgSoft） */
internal val SoftCard = Color(0xFFF2F3F5)
internal val Danger = Color(0xFFD92D20)

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun OutlinePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, Line), content = content)
}

/** 小节标签：12sp 灰字，对齐 Penly SectionTitle */
@Composable
internal fun SettingsSectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        color = Quiet,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** 分组卡片：浅灰底，多行共用一卡（对齐 Penly SettingCard） */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SoftCard,
        content = { Column(content = content) },
    )
}

/**
 * 紧凑设置行：标题 + 可选副标题 + 可选 trailing（对齐 Penly SettingRow）。
 * padding vertical 13dp，比旧 SettingsAction（18dp 独立描边卡）更密。
 */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** 父开关下的子条目缩进 */
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (indent) 28.dp else 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                color = if (danger) Danger else Ink,
                fontSize = if (indent) 14.sp else 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Quiet, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Green,
                    checkedThumbColor = Color.White,
                ),
            )
        },
    )
}

@Composable
internal fun FineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(horizontal = 16.dp), color = Color(0xFFE4E5E7), thickness = 1.dp)
}
