package com.bhavani.crypto.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bg = Color(0xFF080A0E)
private val Panel = Color(0xFF10141B)
private val Text = Color(0xFFF4F7FA)
private val Muted = Color(0xFF8C97A8)
private val Accent = Color(0xFF7CFFB2)

private val Dark = darkColorScheme(
    primary=Accent, onPrimary=Color.Black, background=Bg, surface=Panel,
    onBackground=Text, onSurface=Text, secondary=Muted
)

@Composable fun CryptoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=Dark, typography=Typography(), content=content)
}
