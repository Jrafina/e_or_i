package com.ddl.eori.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 品牌色：偏墨绿，克制、不像“理财广告”，深浅色都舒适
private val Brand = Color(0xFF0F6B5C)
private val BrandLight = Color(0xFF7FD8C4)
private val ExpenseColor = Color(0xFFB3261E)
private val IncomeColor = Color(0xFF1F7A3D)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EFE1),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635C),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    error = ExpenseColor,
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFB8EFE1),
    secondary = Color(0xFFB2CCC4),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    error = Color(0xFFFFB4AB),
)

/** 收支语义色，供账单行、图表复用 */
object AmountColors {
    val income = IncomeColor
    val expense = ExpenseColor
    val incomeDark = Color(0xFF7ADB9A)
    val expenseDark = Color(0xFFFFB4AB)
}

val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun EoriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 跟随系统时保留动态取色；用户手动选择深色时关闭，避免配色跳变 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
