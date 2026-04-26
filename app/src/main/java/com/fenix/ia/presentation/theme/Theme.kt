package com.fenix.ia.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FenixDark = darkColorScheme(
    primary = Color(0xFFE8854F),
    secondary = Color(0xFFB05D2E),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

private val FenixLight = lightColorScheme(
    primary = Color(0xFFE8854F),
    secondary = Color(0xFFB05D2E),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121)
)

@Composable
fun FenixTheme(
    darkTheme: Boolean = true, // App oscura por defecto — mejor legibilidad en campo
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) FenixDark else FenixLight
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
