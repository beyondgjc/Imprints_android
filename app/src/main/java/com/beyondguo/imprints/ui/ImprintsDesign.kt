package com.beyondguo.imprints.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun OutlinePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, Line), content = content)
}

@Composable
internal fun SettingsAction(title: String, subtitle: String, trailing: String = "›", onClick: () -> Unit) {
    OutlinePanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle, color = Quiet, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text(trailing, color = Quiet, fontSize = 20.sp)
        }
    }
}

@Composable
internal fun FineDivider() { HorizontalDivider(color = Line, thickness = 1.dp) }
