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

private val weekDays = listOf("一", "二", "三", "四", "五", "六", "日")

private const val MAX_TIMELINE_ROWS = 4

internal fun timelineRowCount(size: DpSize): Int {
    val byDesign = when {
        size.height <= 176.dp -> 2
        size.height <= 268.dp -> 3
        else -> 4
    }
    // 一行连间距 60dp；老启动器高度不足时少放几行，避免最后一行被裁
    val fit = ((size.height.value - 50f) / 60f).toInt().coerceAtLeast(1)
    return minOf(byDesign, fit)
}

class TimelineWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 110.dp), // 老启动器的 4×2
            DpSize(250.dp, 176.dp),
            DpSize(250.dp, 268.dp),
            DpSize(328.dp, 176.dp),
            DpSize(328.dp, 268.dp),
            DpSize(328.dp, 360.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readTimeline(context)
        if (snapshot == null || !isFresh(snapshot.fetchedAt, TTL_TIMELINE)) {
            WidgetRefresher.request(context)
        }
        val palette = widgetPalette(context)
        val entries = snapshot?.items.orEmpty().take(MAX_TIMELINE_ROWS)
        val covers = if (entries.isEmpty()) {
            emptyList<Bitmap?>()
        } else {
            coroutineScope {
                entries.map { entry ->
                    async {
                        entry.cover.takeIf { it.isNotBlank() }
                            ?.let { WidgetImages.cover(context, it, 39f, 52f) }
                    }
                }.awaitAll()
            }
        }
        val cookie = WidgetImages.shape(
            context,
            22f,
            WidgetShape.Cookie,
            palette.resolved(context).primary.toArgb(),
        )
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                TimelineContent(snapshot, palette, covers, cookie)
            }
        }
    }
}

class TimelineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimelineWidget()
}

@androidx.compose.runtime.Composable
private fun TimelineContent(
    snapshot: TimelineSnapshot?,
    palette: WidgetPalette,
    covers: List<Bitmap?>,
    cookie: Bitmap,
) {
    val context = LocalContext.current
    val rows = timelineRowCount(LocalSize.current)
    val tight = rows <= 1
    WRoot(
        background = palette.provider { it.surfaceContainer },
        corner = widgetCornerRadius(context),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(if (tight) 24.dp else 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WText(
                context.getString(R.string.widget_timeline_label),
                palette.provider { it.onSurface },
                if (tight) 13.sp else 14.sp,
                FontWeight.Bold,
            )
            Spacer(GlanceModifier.defaultWeight())
            WeekRow(snapshot, palette, cookie)
        }
        Spacer(GlanceModifier.height(if (tight) 4.dp else 8.dp))
        when {
            snapshot == null -> TimelineMessage(
                context.getString(R.string.widget_loading),
                palette,
            )

            snapshot.items.isEmpty() -> TimelineMessage(
                context.getString(R.string.widget_timeline_empty),
                palette,
            )

            else -> {
                snapshot.items.take(rows).forEachIndexed { index, entry ->
                    if (index > 0) Spacer(GlanceModifier.height(8.dp))
                    TimelineRow(entry, covers.getOrNull(index), palette)
                }
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WeekRow(
    snapshot: TimelineSnapshot?,
    palette: WidgetPalette,
    cookie: Bitmap,
) {
    val context = LocalContext.current
    val today = snapshot?.today ?: 1
    Row {
        weekDays.forEachIndexed { index, day ->
            val dayOfWeek = index + 1
            val isToday = dayOfWeek == today
            val hasUpdate = snapshot?.week?.getOrNull(index) == true
            Box(
                modifier = GlanceModifier
                    .size(22.dp)
                    .clickable(WidgetActions.timelineDay(context, dayOfWeek)),
                contentAlignment = Alignment.Center,
            ) {
                if (isToday) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ImageProvider(cookie), ContentScale.FillBounds),
                        contentAlignment = Alignment.Center,
                    ) {
                        WText(
                            day,
                            palette.provider { it.onPrimary },
                            11.sp,
                            FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                } else {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        WText(
                            day,
                            palette.provider { it.onSurfaceVariant },
                            11.sp,
                            FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (hasUpdate) {
                            Box(
                                GlanceModifier
                                    .size(4.dp)
                                    .background(palette.provider { it.primary })
                                    .cornerRadius(2.dp)
                            ) {}
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TimelineRow(
    entry: TimelineEntry,
    cover: Bitmap?,
    palette: WidgetPalette,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(WidgetActions.season(context, entry.seasonId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(39.dp).height(52.dp)) {
            if (cover != null) {
                Image(
                    provider = ImageProvider(cover),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(10.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(10.dp)
                        .background(palette.provider { it.surfaceContainerHigh })
                ) {}
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            WText(
                entry.title,
                palette.provider { it.onSurface },
                13.5.sp,
                FontWeight.Bold,
            )
            Spacer(GlanceModifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.followed) {
                    Box(
                        modifier = GlanceModifier
                            .background(palette.provider { it.secondaryContainer })
                            .cornerRadius(5.dp)
                            .padding(horizontal = 5.dp)
                    ) {
                        WText(
                            context.getString(R.string.widget_timeline_followed),
                            palette.provider { it.onSecondaryContainer },
                            10.sp,
                            FontWeight.Medium,
                        )
                    }
                    Spacer(GlanceModifier.width(5.dp))
                }
                WText(
                    buildString {
                        append(entry.episode)
                        if (entry.published && entry.time.isNotBlank()) {
                            append(" · ")
                            append(entry.time)
                        }
                    },
                    palette.provider { it.onSurfaceVariant },
                    11.sp,
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        TimelineStatus(entry, palette)
    }
}

@androidx.compose.runtime.Composable
private fun TimelineStatus(entry: TimelineEntry, palette: WidgetPalette) {
    val context = LocalContext.current
    val published = entry.published
    Box(
        modifier = GlanceModifier
            .background(
                palette.provider {
                    if (published) it.tertiaryContainer else it.surfaceContainerHigh
                }
            )
            .cornerRadius(999.dp)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        WText(
            if (published) {
                context.getString(R.string.widget_timeline_updated)
            } else {
                entry.time
            },
            palette.provider {
                if (published) it.onTertiaryContainer else it.onSurfaceVariant
            },
            11.5.sp,
            FontWeight.Bold,
        )
    }
}

@androidx.compose.runtime.Composable
private fun TimelineMessage(text: String, palette: WidgetPalette) {
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
