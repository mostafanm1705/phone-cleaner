package com.mostafa.phonecleaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mostafa.phonecleaner.device.Status

val GoodColor = Color(0xFF2E9E5B)
val WarnColor = Color(0xFFE59E12)
val BadColor = Color(0xFFD9443B)
val InfoColor = Color(0xFF5B7FD6)

fun Status.color(): Color = when (this) {
    Status.GOOD -> GoodColor
    Status.WARN -> WarnColor
    Status.BAD -> BadColor
    Status.INFO -> InfoColor
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF4A635F),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    secondary = Color(0xFFB1CCC6),
)

@Composable
fun CleanerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
