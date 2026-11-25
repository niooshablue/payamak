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
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    val family = (FontOptions.firstOrNull { it.id == settings.font } ?: FontOptions[0]).family
    MaterialTheme(colorScheme = colors, typography = scaled(family, settings.scale), content = content)
}
