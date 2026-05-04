package com.fenix.ia.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FenixDark = darkColorScheme(
    primary = Color(0xFFFFB238),
    secondary = Color(0xFFFF6A1A),
    tertiary = Color(0xFFE5391D),
    background = Color(0xFF100908),
    surface = Color(0xFF1B1110),
    surfaceVariant = Color(0xFF2A1A18),
    primaryContainer = Color(0xFF5A220D),
    secondaryContainer = Color(0xFF3A1810),
    error = Color(0xFFFF6B5F),
    onPrimary = Color(0xFF231000),
    onBackground = Color(0xFFFFE8D0),
    onSurface = Color(0xFFFFE8D0),
    onSurfaceVariant = Color(0xFFE8B89A)
)

private val FenixLight = lightColorScheme(
    primary = Color(0xFFB74312),
    secondary = Color(0xFFD8651B),
    tertiary = Color(0xFF7A1D10),
    background = Color(0xFFFFF8F1),
    surface = Color(0xFFFFFCF8),
    surfaceVariant = Color(0xFFFFE2C2),
    primaryContainer = Color(0xFFFFD1A3),
    secondaryContainer = Color(0xFFFFE2C2),
    onPrimary = Color.White,
    onBackground = Color(0xFF24100B),
    onSurface = Color(0xFF24100B),
    onSurfaceVariant = Color(0xFF624033)
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
