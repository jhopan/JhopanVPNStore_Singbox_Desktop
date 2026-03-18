package com.jhopanstore.vpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand colors
val Primary = Color(0xFF1976D2)
val PrimaryDark = Color(0xFF1565C0)
val PrimaryLight = Color(0xFF42A5F5)
val Secondary = Color(0xFF26A69A)
val Error = Color(0xFFD32F2F)
val Background = Color(0xFFF5F5F5)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFE3F2FD)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    error = Error,
    onError = Color.White,
    background = Background,
    onBackground = Color(0xFF1C1B1F),
    surface = Surface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003733),
    error = Color(0xFFCF6679),
    onError = Color(0xFF601410),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF1E3A5F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

@Composable
fun JhopanStoreVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
