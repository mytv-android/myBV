package com.github.mytv.bv.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

internal fun widgetCornerRadius(context: Context): Dp {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 28.dp
    val id = context.resources.getIdentifier(
        "system_app_widget_background_radius",
        "dimen",
        "android",
    )
    if (id == 0) return 28.dp
    val px = runCatching { context.resources.getDimension(id) }.getOrDefault(0f)
    val radiusDp = px / context.resources.displayMetrics.density
    return minOf(28f, radiusDp).dp
}

@androidx.compose.runtime.Composable
internal fun WText(
    text: String,
    color: ColorProvider,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text = text,
        modifier = if (align != null) modifier.fillMaxWidth() else modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align,
        ),
        maxLines = maxLines,
    )
}

@androidx.compose.runtime.Composable
internal fun WIcon(
    drawable: Int,
    tint: ColorProvider,
    size: Dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    Image(
        provider = ImageProvider(drawable),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

@androidx.compose.runtime.Composable
internal fun WRoot(
    background: ColorProvider,
    corner: Dp,
    modifier: GlanceModifier = GlanceModifier,
    padding: Dp = 12.dp,
    content: @androidx.compose.runtime.Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(corner)
            .padding(padding),
        content = content,
    )
}

@androidx.compose.runtime.Composable
internal fun WButton(
    text: String,
    icon: Int?,
    background: ColorProvider,
    foreground: ColorProvider,
    action: Action,
    modifier: GlanceModifier = GlanceModifier,
    height: Dp = 40.dp,
) {
    Row(
        modifier = modifier
            .height(height)
            .background(background)
            .cornerRadius((height.value / 2f).dp)
            .clickable(action)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            WIcon(icon, foreground, 18.dp)
            Spacer(GlanceModifier.width(6.dp))
        }
        WText(text, foreground, 13.sp, FontWeight.Medium, maxLines = 1)
    }
}

@androidx.compose.runtime.Composable
internal fun WFab(
    shape: Bitmap,
    icon: Int,
    tint: ColorProvider,
    action: Action,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(ImageProvider(shape), ContentScale.FillBounds)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        WIcon(icon, tint, iconSize)
    }
}

@androidx.compose.runtime.Composable
internal fun WProgressTrack(
    fraction: Float,
    widthDp: Dp,
    trackColor: ColorProvider,
    fillColor: ColorProvider,
) {
    Row(GlanceModifier.fillMaxWidth().height(4.dp).background(trackColor)) {
        if (fraction > 0f) {
            Box(
                GlanceModifier
                    .width((widthDp.value * fraction.coerceIn(0f, 1f)).dp)
                    .fillMaxHeight()
                    .background(fillColor)
            ) {}
        }
    }
}

@androidx.compose.runtime.Composable
internal fun WDurationBadge(text: String) {
    Box(
        GlanceModifier
            .background(ColorProvider(Color(0x8C000000)))
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        WText(text, ColorProvider(Color.White), 10.5.sp, FontWeight.Bold, maxLines = 1)
    }
}

@androidx.compose.runtime.Composable
internal fun WOverlayChip(text: String, icon: Int?, iconSize: Dp = 14.dp) {
    Row(
        modifier = GlanceModifier
            .background(ColorProvider(Color(0x73000000)))
            .cornerRadius(999.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            WIcon(icon, ColorProvider(Color.White), iconSize)
            Spacer(GlanceModifier.width(4.dp))
        }
        WText(text, ColorProvider(Color.White), 11.sp, FontWeight.Bold, maxLines = 1)
    }
}

@androidx.compose.runtime.Composable
internal fun WCountChip(count: Int, palette: WidgetPalette) {
    Box(
        GlanceModifier
            .background(palette.provider { it.secondaryContainer })
            .cornerRadius(999.dp)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        WText(
            count.toString(),
            palette.provider { it.onSecondaryContainer },
            11.sp,
            FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 空状态 / 未登录状态，居中的气泡图标 + 说明 + 可选的登录按钮。 */
@androidx.compose.runtime.Composable
internal fun WEmptyState(
    palette: WidgetPalette,
    title: String,
    hint: String,
    blob: Bitmap,
    blobIcon: Int,
    compact: Boolean = false,
    actionLabel: String? = null,
    actionIcon: Int? = null,
    action: Action? = null,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!compact) {
            Box(
                modifier = GlanceModifier
                    .size(56.dp)
                    .background(ImageProvider(blob), ContentScale.FillBounds),
                contentAlignment = Alignment.Center,
            ) {
                WIcon(blobIcon, palette.provider { it.onTertiaryContainer }, 26.dp)
            }
            Spacer(GlanceModifier.height(6.dp))
        }
        WText(
            title,
            palette.provider { it.onSurface },
            if (compact) 13.sp else 14.sp,
            FontWeight.Bold,
            maxLines = 2,
            align = TextAlign.Center,
        )
        if (!compact) {
            Spacer(GlanceModifier.height(4.dp))
            WText(
                hint,
                palette.provider { it.onSurfaceVariant },
                12.sp,
                FontWeight.Normal,
                maxLines = 2,
                align = TextAlign.Center,
            )
        }
        if (action != null && actionLabel != null) {
            Spacer(GlanceModifier.height(if (compact) 6.dp else 8.dp))
            WButton(
                text = actionLabel,
                icon = actionIcon,
                background = palette.provider { it.primary },
                foreground = palette.provider { it.onPrimary },
                action = action,
                height = if (compact) 32.dp else 36.dp,
            )
        }
    }
}
