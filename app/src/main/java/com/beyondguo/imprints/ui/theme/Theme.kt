package com.beyondguo.imprints.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = BrandGreen,
    background = PaperDark,
    surface = PaperDark,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    secondary = BrandGreen,
    background = PaperLight,
    surface = PaperLight,
)

@Composable
fun ImprintsTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
