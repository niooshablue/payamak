package ir.payamak.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ir.payamak.app.R
import ir.payamak.app.data.AppSettings

data class FontOption(val id: String, val label: String, val family: FontFamily)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int) = FontFamily(
    listOf(400 to FontWeight.Normal, 500 to FontWeight.Medium, 700 to FontWeight.Bold).map { (w, fw) ->
        Font(res, fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

val FontOptions: List<FontOption> by lazy {
    listOf(
        FontOption("vazirmatn", "وزیرمتن", variable(R.font.vazirmatn)),
        FontOption("shabnam", "شبنم", FontFamily(Font(R.font.shabnam_regular, FontWeight.Normal), Font(R.font.shabnam_bold, FontWeight.Bold))),
        FontOption("naskh", "نسخ (نوتو)", variable(R.font.noto_naskh)),
        FontOption("amiri", "امیری", FontFamily(Font(R.font.amiri_regular, FontWeight.Normal), Font(R.font.amiri_bold, FontWeight.Bold)))
    )
}

private fun scaled(family: FontFamily, scale: Float): Typography {
    val b = Typography()
    fun TextStyle.s() = copy(
        fontFamily = family, fontSize = fontSize * scale,
        lineHeight = lineHeight * scale, letterSpacing = 0.sp // حروف فارسی نباید فاصله‌ی حرفی بگیرند
    )
    return Typography(
        displayLarge = b.displayLarge.s(), displayMedium = b.displayMedium.s(), displaySmall = b.displaySmall.s(),
        headlineLarge = b.headlineLarge.s(), headlineMedium = b.headlineMedium.s(), headlineSmall = b.headlineSmall.s(),
        titleLarge = b.titleLarge.s(), titleMedium = b.titleMedium.s(), titleSmall = b.titleSmall.s(),
        bodyLarge = b.bodyLarge.s(), bodyMedium = b.bodyMedium.s(), bodySmall = b.bodySmall.s(),
        labelLarge = b.labelLarge.s(), labelMedium = b.labelMedium.s(), labelSmall = b.labelSmall.s()
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00796B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A6360), tertiary = Color(0xFF456179)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC), onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC8), tertiary = Color(0xFFADCAE6)
)

@Composable
fun PayamakTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val preset = when (settings.design) {
        0 -> if (dark) DarkColors else LightColors
        1 -> if (dark) darkColorScheme(primary = Color(0xFF8AA9FF), secondary = Color(0xFFB8C7F5)) else lightColorScheme(primary = Color(0xFF245BDB), secondary = Color(0xFF526AA8))
        2 -> if (dark) darkColorScheme(primary = Color(0xFF9CCBFF), secondary = Color(0xFFB8C9DA)) else lightColorScheme(primary = Color(0xFF1677C8), secondary = Color(0xFF526779))
        3 -> if (dark) darkColorScheme(primary = Color(0xFF65D6C0), secondary = Color(0xFFB2CCC5)) else lightColorScheme(primary = Color(0xFF008F78), secondary = Color(0xFF4A6860))
        4 -> if (dark) darkColorScheme(primary = Color(0xFFFFB1C8), secondary = Color(0xFFE5B8C8)) else lightColorScheme(primary = Color(0xFFB43D70), secondary = Color(0xFF78536A))
        else -> if (dark) darkColorScheme(primary = Color(0xFFFFC477), secondary = Color(0xFFD8C1A2)) else lightColorScheme(primary = Color(0xFF9A5B12), secondary = Color(0xFF765B3D))
    }
    val colors = preset.copy(
        primary = settings.primaryColor.takeIf { it != 0L }?.let(::Color) ?: preset.primary,
        background = settings.backgroundColor.takeIf { it != 0L }?.let(::Color) ?: preset.background,
        surface = settings.cardColor.takeIf { it != 0L }?.let(::Color) ?: preset.surface,
        onSurface = settings.textColor.takeIf { it != 0L }?.let(::Color) ?: preset.onSurface
    )
    val family = (FontOptions.firstOrNull { it.id == settings.font } ?: FontOptions[0]).family
    MaterialTheme(colorScheme = colors, typography = scaled(family, settings.scale), content = content)
}
