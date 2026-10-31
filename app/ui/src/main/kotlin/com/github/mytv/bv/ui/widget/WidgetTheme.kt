package com.github.mytv.bv.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider
import com.github.mytv.bv.entity.DarkMode
import com.github.mytv.bv.entity.ThemePrefs
import com.github.mytv.bv.entity.ThemeSource
import com.github.mytv.bv.ui.designsystem.theme.CoverSeedCache
import com.github.mytv.bv.util.Prefs
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 小组件配色：和 App 一样用 material-kolor 从一个种子色生成浅、深两套 SchemeVibrant 方案。
 * 明暗跟随系统时给 Glance 一个 day/night 的 ColorProvider，强制明暗时给固定色。
 */
internal class WidgetPalette(
    val light: ColorScheme,
    val dark: ColorScheme,
    private val forcedDark: Boolean?,
) {
    fun provider(role: (ColorScheme) -> Color): ColorProvider =
        if (forcedDark != null) {
            ColorProvider(role(if (forcedDark) dark else light))
        } else {
            DayNightColorProvider(day = role(light), night = role(dark))
        }

    /** 画 Bitmap（形状、封面遮罩）时用当天实际生效的一套颜色。 */
    fun resolved(context: Context): ColorScheme = when (forcedDark) {
        true -> dark
        false -> light
        null -> if (isNight(context)) dark else light
    }

    fun glanceColors(): ColorProviders = colorProviders(
        primary = provider { it.primary },
        onPrimary = provider { it.onPrimary },
        primaryContainer = provider { it.primaryContainer },
        onPrimaryContainer = provider { it.onPrimaryContainer },
        secondary = provider { it.secondary },
        onSecondary = provider { it.onSecondary },
        secondaryContainer = provider { it.secondaryContainer },
        onSecondaryContainer = provider { it.onSecondaryContainer },
        tertiary = provider { it.tertiary },
        onTertiary = provider { it.onTertiary },
        tertiaryContainer = provider { it.tertiaryContainer },
        onTertiaryContainer = provider { it.onTertiaryContainer },
        error = provider { it.error },
        errorContainer = provider { it.errorContainer },
        onError = provider { it.onError },
        onErrorContainer = provider { it.onErrorContainer },
        background = provider { it.background },
        onBackground = provider { it.onBackground },
        surface = provider { it.surface },
        onSurface = provider { it.onSurface },
        surfaceVariant = provider { it.surfaceContainerHigh },
        onSurfaceVariant = provider { it.onSurfaceVariant },
        outline = provider { it.outline },
        inverseOnSurface = provider { it.inverseOnSurface },
        inverseSurface = provider { it.inverseSurface },
        inversePrimary = provider { it.inversePrimary },
        widgetBackground = provider { it.surfaceContainer },
    )
}

private data class SchemeKey(
    val seed: Int,
    val dark: Boolean,
    val amoled: Boolean,
    val style: Int,
    val contrast: Int,
)

private val schemeCache = LruCache<SchemeKey, ColorScheme>(24)

private fun buildScheme(
    seed: Color,
    dark: Boolean,
    amoled: Boolean,
    prefs: ThemePrefs,
): ColorScheme {
    val key = SchemeKey(
        seed = seed.toArgb(),
        dark = dark,
        amoled = amoled,
        style = prefs.style.ordinal,
        contrast = (prefs.contrast * 1000).toInt(),
    )
    schemeCache.get(key)?.let { return it }
    val scheme = dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = amoled,
        style = prefs.style,
        contrastLevel = prefs.contrast,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    schemeCache.put(key, scheme)
    return scheme
}

private fun isNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

/**
 * @param coverUrl 继续观看、刷刷传自己的封面；只有「详情页使用封面颜色」打开时（刷刷除外）才用它做种子
 * @param forceDark 刷刷固定深色
 * @param alwaysCoverSeed 刷刷的播放键始终用封面色
 */
internal suspend fun widgetPalette(
    context: Context,
    coverUrl: String? = null,
    forceDark: Boolean = false,
    alwaysCoverSeed: Boolean = false,
): WidgetPalette = withContext(Dispatchers.Default) {
    val prefs = runCatching { Prefs.themePrefsFlow.first() }.getOrDefault(ThemePrefs())
    val forced = when {
        forceDark -> true
        prefs.darkMode == DarkMode.System -> null
        prefs.darkMode == DarkMode.Light -> false
        else -> true
    }
    val darkNow = forced ?: isNight(context)
    val amoled = darkNow && prefs.darkMode == DarkMode.PureBlack
    val globalSeed = when {
        prefs.source == ThemeSource.Wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            Color(context.getColor(android.R.color.system_accent1_500))

        prefs.source == ThemeSource.Content -> prefs.contentSeed ?: prefs.seed
        else -> prefs.seed
    }
    val coverSeed = coverUrl
        ?.takeIf { alwaysCoverSeed || prefs.coverTheme }
        ?.let { CoverSeedCache.peek(widgetImageUrl(it)) }
    val seed = coverSeed ?: globalSeed
    WidgetPalette(
        light = buildScheme(seed, dark = false, amoled = false, prefs = prefs),
        dark = buildScheme(seed, dark = true, amoled = amoled, prefs = prefs),
        forcedDark = forced,
    )
}
