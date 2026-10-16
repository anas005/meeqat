package com.anas.meeqat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

data class MeeqatPalette(
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val forest: Color,
    val accentInk: Color,
    val sun: Color,
    val tint: Color,
    val dayFill: Color,
    val nightFill: Color,
    val chartAccent: Color,
)

val LightPalette = MeeqatPalette(
    background = Color(0xFFE7EFEA),
    surface = Color.White,
    ink = Color(0xFF12261F),
    muted = Color(0xFF4A5F57),
    forest = Color(0xFF1F5F4F),
    accentInk = Color(0xFF12261F),
    sun = Color(0xFFFFD66B),
    tint = Color(0xFFCFE3DA),
    dayFill = Color(0xFFF3C75A),
    nightFill = Color(0xFFBFD6CB),
    chartAccent = Color(0xFF1F5F4F),
)

val DarkPalette = MeeqatPalette(
    background = Color(0xFF1B1C2E),
    surface = Color(0xFF25263A),
    ink = Color(0xFFF2F0E8),
    muted = Color(0xFFA8BDAE),
    forest = Color(0xFF304B45),
    accentInk = Color(0xFF1B1C2E),
    sun = Color(0xFFF7F72E),
    tint = Color(0xFF35384D),
    dayFill = Color(0xFFE8A020),
    nightFill = Color(0xFF4B5F95),
    chartAccent = Color(0xFFF1D27A),
)

val LocalMeeqatPalette = staticCompositionLocalOf { LightPalette }
