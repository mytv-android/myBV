package com.github.mytv.bv.ui.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import androidx.glance.text.TextAlign
import com.github.mytv.bv.appui.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val MAX_LATER_ROWS = 5

internal fun laterRowCount(size: DpSize): Int {
    val byDesign = when {
        size.height <= 176.dp -> 2
        size.height <= 268.dp -> 3
        size.height <= 360.dp -> 4
        else -> 5
    }
    // 老启动器给的 4×2 可能只有 ~110dp：按可放下的一行 61dp 再压一层
    val fit = ((size.height.value - 50f) / 61f).toInt().coerceAtLeast(1)
    return minOf(byDesign, fit)
}

class LaterWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 110.dp), // 老启动器的 4×2
            DpSize(250.dp, 176.dp),
            DpSize(250.dp, 268.dp),
            DpSize(328.dp, 176.dp),
            DpSize(328.dp, 268.dp),
            DpSize(328.dp, 360.dp),
            DpSize(328.dp, 452.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readLater(context)
        if (snapshot == null || !isFresh(snapshot.fetchedAt, TTL_LATER)) {
            WidgetRefresher.request(context)
        }
        val palette = widgetPalette(context)
        val items = snapshot?.items.orEmpty().take(MAX_LATER_ROWS)
        val thumbs = if (items.isEmpty()) {
            emptyList<Bitmap?>()
        } else {
            coroutineScope {
                items.map { item ->
                    async {
                        item.cover.takeIf { it.isNotBlank() }
                            ?.let { WidgetImages.cover(context, it, 88f, 55f) }
                    }
                }.awaitAll()
            }
        }
        val resolved = palette.resolved(context)
        val clover = WidgetImages.shape(context, 32f, WidgetShape.Clover, resolved.tertiaryContainer.toArgb())
        val blob = WidgetImages.shape(context, 56f, WidgetShape.Cookie, resolved.tertiaryContainer.toArgb())
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                LaterContent(snapshot, palette, thumbs, clover, blob)
            }
        }
    }
}

class LaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LaterWidget()
}

@androidx.compose.runtime.Composable
private fun LaterContent(
    snapshot: LaterSnapshot?,
    palette: WidgetPalette,
    thumbs: List<Bitmap?>,
    clover: Bitmap,
    blob: Bitmap,
) {
    val context = LocalContext.current
    val rows = laterRowCount(LocalSize.current)
    val compact = rows <= 2
    val tight = rows <= 1
    val hasList = snapshot?.loggedIn == true && snapshot.items.isNotEmpty()
    // 高度只剩一行时，空/未登录状态去掉标题栏，给按钮留出空间
    val showHeader = !tight || hasList
    WRoot(
        background = palette.provider { it.surfaceContainer },
        corner = widgetCornerRadius(context),
    ) {
        if (showHeader) {
            LaterHeader(
                total = if (snapshot?.loggedIn == true) snapshot.total else null,
                palette = palette,
                clover = clover,
                tight = tight,
            )
            Spacer(GlanceModifier.height(if (tight) 2.dp else if (compact) 4.dp else 10.dp))
        }
        when {
            snapshot == null -> LaterMessage(
                text = context.getString(R.string.widget_loading),
                palette = palette,
            )

            !snapshot.loggedIn -> WEmptyState(
                palette = palette,
                title = context.getString(R.string.widget_login_later_title),
                hint = context.getString(R.string.widget_login_hint),
                blob = blob,
                blobIcon = R.drawable.widget_ic_person,
                compact = LocalSize.current.height < 300.dp,
                actionLabel = context.getString(R.string.widget_login),
                actionIcon = R.drawable.widget_ic_person,
                action = WidgetActions.login(context),
            )

            snapshot.items.isEmpty() -> WEmptyState(
                palette = palette,
                title = context.getString(R.string.widget_later_empty_title),
                hint = context.getString(R.string.widget_later_empty_hint),
                blob = blob,
                blobIcon = R.drawable.widget_ic_later,
                compact = LocalSize.current.height < 300.dp,
            )

            else -> {
                snapshot.items.take(rows).forEachIndexed { index, item ->
                    if (index > 0) Spacer(GlanceModifier.height(if (compact) 6.dp else 10.dp))
                    LaterRow(item, thumbs.getOrNull(index), palette)
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaterHeader(total: Int?, palette: WidgetPalette, clover: Bitmap, tight: Boolean) {
    val context = LocalContext.current
    val height = if (tight) 24.dp else 32.dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(height)
                .background(ImageProvider(clover), ContentScale.FillBounds),
            contentAlignment = Alignment.Center,
        ) {
            WIcon(
                R.drawable.widget_ic_later,
                palette.provider { it.onTertiaryContainer },
                if (tight) 14.dp else 18.dp,
            )
        }
        Spacer(GlanceModifier.width(if (tight) 6.dp else 8.dp))
        WText(
            context.getString(R.string.widget_later_label),
            palette.provider { it.onSurface },
            if (tight) 13.sp else 15.sp,
            FontWeight.Bold,
        )
        if (total != null) {
            Spacer(GlanceModifier.width(6.dp))
            WCountChip(total, palette)
        }
        Spacer(GlanceModifier.defaultWeight())
        Box(
            modifier = GlanceModifier
                .size(height)
                .background(palette.provider { it.surfaceContainerHigh })
                .cornerRadius(height / 2)
                .clickable(WidgetActions.toView(context)),
            contentAlignment = Alignment.Center,
        ) {
            WIcon(
                R.drawable.widget_ic_chevron,
                palette.provider { it.onSurfaceVariant },
                if (tight) 16.dp else 20.dp,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaterRow(item: WidgetVideo, thumb: Bitmap?, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(WidgetActions.resume(context, item)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(88.dp).height(55.dp)) {
            if (thumb != null) {
                Image(
                    provider = ImageProvider(thumb),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(12.dp)
                        .background(palette.provider { it.surfaceContainerHigh })
                ) {}
            }
            if (item.duration > 0) {
                Row(
                    modifier = GlanceModifier.fillMaxSize().padding(4.dp),
                    horizontalAlignment = Alignment.End,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    WDurationBadge(formatClock(item.duration))
                }
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            WText(
                item.title,
                palette.provider { it.onSurface },
                13.sp,
                FontWeight.Medium,
                maxLines = 2,
            )
            Spacer(GlanceModifier.height(3.dp))
            WText(
                item.upName,
                palette.provider { it.onSurfaceVariant },
                11.sp,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaterMessage(text: String, palette: WidgetPalette) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WText(
            text,
            palette.provider { it.onSurfaceVariant },
            12.sp,
            align = TextAlign.Center,
        )
    }
}
