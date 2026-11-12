package com.matty.cadence.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.matty.cadence.R

/** A reading typeface. Bundled fonts are SIL Open Font License (licenses/fonts-OFL.txt). */
data class ReadingFont(val id: String, val name: String, val note: String, val family: FontFamily)

val readingFonts = listOf(
    ReadingFont("literata", "Literata", "closest to Bookerly", FontFamily(
        Font(R.font.literata_400, FontWeight.Normal), Font(R.font.literata_600, FontWeight.SemiBold))),
    ReadingFont("sourceserif", "Source Serif", "crisp, modern", FontFamily(
        Font(R.font.source_serif_4_400, FontWeight.Normal), Font(R.font.source_serif_4_600, FontWeight.SemiBold))),
    ReadingFont("lora", "Lora", "warm, calligraphic", FontFamily(
        Font(R.font.lora_400, FontWeight.Normal), Font(R.font.lora_600, FontWeight.SemiBold))),
    ReadingFont("garamond", "EB Garamond", "classic book face", FontFamily(
        Font(R.font.eb_garamond_400, FontWeight.Normal), Font(R.font.eb_garamond_600, FontWeight.SemiBold))),
    ReadingFont("merriweather", "Merriweather", "sturdy, very legible", FontFamily(
        Font(R.font.merriweather_400, FontWeight.Normal), Font(R.font.merriweather_700, FontWeight.Bold))),
    ReadingFont("atkinson", "Atkinson Hyperlegible", "built for low vision", FontFamily(
        Font(R.font.atkinson_hyperlegible_400, FontWeight.Normal), Font(R.font.atkinson_hyperlegible_700, FontWeight.Bold))),
    ReadingFont("serif", "Noto Serif", "Android's book serif", FontFamily.Serif),
    ReadingFont("sans", "Roboto", "Android's system sans", FontFamily.SansSerif),
)

/** Amharic uses the system Ethiopic face (Android ships Noto Ethiopic; Apple's Kefa isn't on Android). */
val amharicFamily: FontFamily = FontFamily.Serif

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light") }
enum class PageStyle(val label: String) { PARCHMENT("Parchment"), THEME("Same as theme") }
enum class Margins(val label: String, val inset: Float) { NARROW("Narrow", 0.06f), NORMAL("Normal", 0.11f), WIDE("Wide", 0.17f) }
enum class LineSpacing(val label: String, val factor: Float) { COMPACT("Compact", 1.25f), NORMAL("Normal", 1.45f), RELAXED("Relaxed", 1.7f) }
enum class ReaderMode { SINGLE, FLOWING, CARD }

/** Everything the reader remembers between sessions. Compose state, so screens redraw on change. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(enumOr(p.getString("theme", null), ThemeMode.DARK))
        private set
    var page by mutableStateOf(enumOr(p.getString("page", null), PageStyle.PARCHMENT))
        private set
    var fontId by mutableStateOf(p.getString("font", "literata")!!)
        private set
    var sizeStep by mutableIntStateOf(p.getInt("size", 0))
        private set
    var margins by mutableStateOf(enumOr(p.getString("margins", null), Margins.NORMAL))
        private set
    var spacing by mutableStateOf(enumOr(p.getString("spacing", null), LineSpacing.NORMAL))
        private set
    var wpm by mutableDoubleStateOf(p.getFloat("wpm", 200f).toDouble())
        private set
    var rate by mutableFloatStateOf(p.getFloat("rate", 1f))
        private set
    var mode by mutableStateOf(enumOr(p.getString("mode", null), ReaderMode.SINGLE))
        private set

    val font: ReadingFont get() = readingFonts.firstOrNull { it.id == fontId } ?: readingFonts[0]
    val scale: Float get() = 1f + 0.12f * sizeStep

    fun chooseTheme(v: ThemeMode) { themeMode = v; p.edit().putString("theme", v.name).apply() }
    fun choosePage(v: PageStyle) { page = v; p.edit().putString("page", v.name).apply() }
    fun chooseFont(v: String) { fontId = v; p.edit().putString("font", v).apply() }
    fun chooseSize(v: Int) { sizeStep = v.coerceIn(-3, 5); p.edit().putInt("size", sizeStep).apply() }
    fun chooseMargins(v: Margins) { margins = v; p.edit().putString("margins", v.name).apply() }
    fun chooseSpacing(v: LineSpacing) { spacing = v; p.edit().putString("spacing", v.name).apply() }
    fun chooseWpm(v: Double) { wpm = v; p.edit().putFloat("wpm", v.toFloat()).apply() }
    fun chooseRate(v: Float) { rate = v; p.edit().putFloat("rate", v).apply() }
    fun chooseMode(v: ReaderMode) { mode = v; if (v != ReaderMode.CARD) p.edit().putString("mode", v.name).apply() }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)
}

/** Black and white app chrome (Dark / Light), parchment reading page — the same look as the TV app. */
data class Palette(
    val bg: Color,
    val bgGlow: Color,
    val text: Color,
    val secondary: Color,
    val selected: Color,
    val glass: Color,
    val onFocus: Color,
    val pageBase: Color,
    val pageLight: Color,
    val pageEdge: Color,
    val ink: Color,
    val inkSoft: Color,
    val dark: Boolean,
)

fun palette(prefs: Prefs): Palette {
    val dark = prefs.themeMode == ThemeMode.DARK
    val text = if (dark) Color(0xFFF4F4F2) else Color(0xFF111113)
    val sand = if (dark) Color(0xFFA6A6A6) else Color(0xFF5A5A5C)
    val bg = if (dark) Color(0xFF0A0A0B) else Color(0xFFF4F4F1)
    val parchment = prefs.page == PageStyle.PARCHMENT
    return Palette(
        bg = bg,
        bgGlow = if (dark) Color(0xFF1F1F22) else Color(0xFFFFFFFF),
        text = text,
        secondary = sand,
        selected = if (dark) Color(0xFF4A4A4D) else Color(0xFFCACACC),
        glass = text.copy(alpha = if (dark) 0.12f else 0.08f),
        onFocus = if (dark) Color(0xFF0A0A0B) else Color(0xFFFAFAF8),
        pageBase = if (parchment) Color(0xFFE3D1AA) else bg,
        pageLight = if (parchment) Color(0xFFF4EAD3) else (if (dark) Color(0xFF1F1F22) else Color.White),
        pageEdge = if (parchment) Color(0xFF6B4A26) else Color.Transparent,
        ink = if (parchment) Color(0xFF3B2A1A) else text,
        inkSoft = if (parchment) Color(0xFF7A5C3E) else sand,
        dark = dark,
    )
}
