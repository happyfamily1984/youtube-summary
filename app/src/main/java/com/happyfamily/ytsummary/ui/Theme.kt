package com.happyfamily.ytsummary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxSize

private fun androidx.compose.ui.Modifier.fillMaxSizeCompat() = this.fillMaxSize()

object C {
    val bg = Color(0xFFF4F2FF)
    val surface = Color.White
    val ink = Color(0xFF14161F)
    val sub = Color(0xFF626879)
    val line = Color(0xFFE6E2F5)
    val brand = Color(0xFF5B3BF0)
    val brand2 = Color(0xFFEEEAFF)
    val ok = Color(0xFF12A150)
    val okBg = Color(0xFFE7F7EE)
    val warn = Color(0xFF5B3BF0)
    val warnBg = Color(0xFFF1EEFF)
    val yt = Color(0xFFD92D20)
    val ytBg = Color(0xFFFDECEC)
    val codeBg = Color(0xFFF1F2F7)
    val dashed = Color(0xFFC9CCDA)
    val muted = Color(0xFF9A9FB0)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.brand, onPrimary = Color.White,
            primaryContainer = C.brand2, onPrimaryContainer = C.brand,
            background = C.bg, onBackground = C.ink,
            surface = C.surface, onSurface = C.ink,
            surfaceVariant = Color(0xFFECEEF5), onSurfaceVariant = C.sub,
            outline = C.line,
        ),
        content = content,
    )
}

/** 시안의 연보라 그라데이션 배경 + 흐린 원 */
@androidx.compose.runtime.Composable
fun AppBackground(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSizeCompat()
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFEDE8FF), Color(0xFFF7F6FD))))
                drawCircle(Color(0x33B9A5FF), radius = size.width * 0.45f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.02f))
                drawCircle(Color(0x22C9B8FF), radius = size.width * 0.35f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.05f, size.height * 0.9f))
            }
    ) { content() }
}
