package kz.sultan.spendlimit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kz.sultan.spendlimit.data.prefs.ThemeMode

// Базовые палитры Material 3. Кастомный брендовый цвет — задел на будущее;
// пока хватает дефолтных схем, главное — корректный контраст в обоих режимах.
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Тема приложения. Режим ([ThemeMode]) приходит из настроек:
 *  - SYSTEM — следовать системной теме;
 *  - LIGHT / DARK — принудительно.
 */
@Composable
fun SpendLimitTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}

/**
 * Цвет для положительных сумм (доходы). У Material нет «зелёного» слота, а тёмный
 * #0F8A5F на тёмном фоне читается плохо — выбираем по светлоте текущей поверхности,
 * поэтому работает и для принудительной темы, не только для системной.
 */
val positiveColor: Color
    @Composable get() =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF4ADE80)
        else Color(0xFF0F8A5F)
