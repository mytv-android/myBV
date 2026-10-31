package com.github.mytv.bv.ui.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.github.mytv.bv.appui.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal enum class ContinueVariant { Square, Wide, Tall }

internal fun continueVariant(size: DpSize): ContinueVariant = when {
    size.width < 280.dp -> ContinueVariant.Square
    size.height >= 240.dp -> ContinueVariant.Tall
    else -> ContinueVariant.Wide
}

class ContinueWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp), // 老启动器的 2×2
            DpSize(160.dp, 176.dp),
            DpSize(250.dp, 110.dp), // 老启动器的 4×2
            DpSize(250.dp, 176.dp),
            DpSize(328.dp, 176.dp),
            DpSize(328.dp, 268.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readContinue(context)
        if (snapshot == null || !isFresh(snapshot.fetchedAt, TTL_CONTINUE)) {
            WidgetRefresher.request(context)
        }
        val video = snapshot?.video
        val palette = widgetPalette(context, coverUrl = video?.cover)
        val url = video?.cover?.takeIf { it.isNotBlank() }
        // 横版 / 竖版各一张：Square、Tall 用横版，Wide 用竖版，具体版式由 LocalSize 决定
        val covers = if (url == null) {
            emptyList<Bitmap?>()
        } else {
            coroutineScope {
                listOf(
                    async { WidgetImages.cover(context, url, 304f, 140f) },
                    async { WidgetImages.cover(context, url, 132f, 152f) },
                ).awaitAll()
            }
        }
        val resolved = palette.resolved(context)
        val fab = WidgetImages.shape(context, 40f, WidgetShape.Sunny, resolved.primary.toArgb())
        val blob = WidgetImages.shape(context, 56f, WidgetShape.Cookie, resolved.tertiaryContainer.toArgb())
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                ContinueContent(
                    snapshot = snapshot,
                    palette = palette,
                    squareCover = covers.getOrNull(0),
                    wideCover = covers.getOrNull(1),
                    tallCover = covers.getOrNull(0),
                    fab = fab,
                    blob = blob,
                )
            }
        }
    }
}

class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWidget()
}

@androidx.compose.runtime.Composable
private fun ContinueContent(
    snapshot: ContinueSnapshot?,
    palette: WidgetPalette,
    squareCover: Bitmap?,
    wideCover: Bitmap?,
    tallCover: Bitmap?,
    fab: Bitmap,
    blob: Bitmap,
) {
    val context = LocalContext.current
    val variant = continueVariant(LocalSize.current)
    WRoot(
        background = palette.provider { it.surfaceContainer },
        corner = widgetCornerRadius(context),
    ) {
        val video = snapshot?.video
        when {
            snapshot == null -> ContinueLoading(palette)
            !snapshot.loggedIn -> WEmptyState(
                palette = palette,
                title = context.getString(R.string.widget_login_continue_title),
                hint = context.getString(R.string.widget_login_hint),
                blob = blob,
                blobIcon = R.drawable.widget_ic_person,
                compact = LocalSize.current.height < 220.dp,
                actionLabel = context.getString(R.string.widget_login),
                actionIcon = R.drawable.widget_ic_person,
                action = WidgetActions.login(context),
            )

            video == null -> WEmptyState(
                palette = palette,
                title = context.getString(R.string.widget_continue_empty_title),
                hint = context.getString(R.string.widget_continue_empty_hint),
                blob = blob,
                blobIcon = R.drawable.widget_ic_play,
                compact = LocalSize.current.height < 220.dp,
            )

            variant == ContinueVariant.Square -> ContinueSquare(video, palette, squareCover, fab)
            variant == ContinueVariant.Tall -> ContinueTall(video, palette, tallCover)
            else -> ContinueWide(video, palette, wideCover)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ContinueLoading(palette: WidgetPalette) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WText(
            context.getString(R.string.widget_loading),
            palette.provider { it.onSurfaceVariant },
            12.sp,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ContinueSquare(
    video: WidgetVideo,
    palette: WidgetPalette,
    cover: Bitmap?,
    fab: Bitmap,
) {
    val context = LocalContext.current
    val trackColor = ColorProvider(Color(0x52FFFFFF))
    val compact = LocalSize.current.height < 150.dp
    val coverHeight = if (compact) 55.dp else 85.dp
    val coverWidth = (LocalSize.current.width - 24.dp).coerceAtLeast(1.dp)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(coverHeight)
            .clickable(WidgetActions.videoDetail(context, video)),
    ) {
        ContinueCover(cover, palette, GlanceModifier.fillMaxSize())
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
        ) {
            WProgressTrack(
                fraction = video.progressFraction,
                widthDp = coverWidth,
                trackColor = trackColor,
                fillColor = palette.provider { it.primary },
            )
        }
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            WFab(
                shape = fab,
                icon = R.drawable.widget_ic_play,
                tint = palette.provider { it.onPrimary },
                action = WidgetActions.resume(context, video),
                size = if (compact) 32.dp else 40.dp,
                iconSize = if (compact) 16.dp else 20.dp,
            )
        }
    }
    Spacer(GlanceModifier.height(4.dp))
    WText(
        video.title,
        palette.provider { it.onSurface },
        13.sp,
        FontWeight.Bold,
        maxLines = if (compact) 1 else 2,
    )
    if (!compact) {
        Spacer(GlanceModifier.height(2.dp))
        WText(
            continueMeta(context, video),
            palette.provider { it.onSurfaceVariant },
            11.sp,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ContinueWide(
    video: WidgetVideo,
    palette: WidgetPalette,
    cover: Bitmap?,
) {
    val context = LocalContext.current
    // 老启动器的 4×2 实际高度可能只有 ~110dp，隐藏 eyebrow / 副信息，保证按钮可见
    val compact = LocalSize.current.height < 140.dp
    Row(GlanceModifier.fillMaxSize()) {
        Box(
            modifier = GlanceModifier
                .width(132.dp)
                .fillMaxHeight()
                .clickable(WidgetActions.videoDetail(context, video)),
        ) {
            ContinueCover(cover, palette, GlanceModifier.fillMaxSize())
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom,
            ) {
                WProgressTrack(
                    fraction = video.progressFraction,
                    widthDp = 132.dp,
                    trackColor = ColorProvider(Color(0x52FFFFFF)),
                    fillColor = palette.provider { it.primary },
                )
            }
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            if (!compact) {
                WText(
                    context.getString(R.string.widget_continue_eyebrow),
                    palette.provider { it.primary },
                    11.sp,
                    FontWeight.Bold,
                )
                Spacer(GlanceModifier.height(2.dp))
            }
            WText(
                video.title,
                palette.provider { it.onSurface },
                14.sp,
                FontWeight.Bold,
                maxLines = if (compact) 2 else 3,
            )
            if (!compact) {
                Spacer(GlanceModifier.height(2.dp))
                WText(
                    continueMeta(context, video),
                    palette.provider { it.onSurfaceVariant },
                    11.sp,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            WButton(
                text = context.getString(
                    R.string.widget_continue_resume,
                    formatClock(video.progress),
                ),
                icon = R.drawable.widget_ic_play,
                background = palette.provider { it.primary },
                foreground = palette.provider { it.onPrimary },
                action = WidgetActions.resume(context, video),
                modifier = GlanceModifier.fillMaxWidth(),
                height = if (compact) 34.dp else 40.dp,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ContinueTall(
    video: WidgetVideo,
    palette: WidgetPalette,
    cover: Bitmap?,
) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < 260.dp
    val coverHeight = if (compact) 110.dp else 140.dp
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(coverHeight)
            .clickable(WidgetActions.videoDetail(context, video)),
    ) {
        ContinueCover(cover, palette, GlanceModifier.fillMaxSize())
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
        ) {
            WProgressTrack(
                fraction = video.progressFraction,
                widthDp = (LocalSize.current.width - 24.dp).coerceAtLeast(1.dp),
                trackColor = ColorProvider(Color(0x52FFFFFF)),
                fillColor = palette.provider { it.primary },
            )
        }
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.Top,
        ) {
            WOverlayChip(context.getString(R.string.widget_continue_eyebrow), icon = null)
        }
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (video.duration > 0) WDurationBadge(formatClock(video.duration))
        }
    }
    Spacer(GlanceModifier.height(6.dp))
    WText(
        video.title,
        palette.provider { it.onSurface },
        14.sp,
        FontWeight.Bold,
        maxLines = 2,
    )
    Spacer(GlanceModifier.height(4.dp))
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            WText(
                video.upName,
                palette.provider { it.onSurfaceVariant },
                11.sp,
            )
            if (!compact) {
                Spacer(GlanceModifier.height(2.dp))
                WText(
                    context.getString(R.string.widget_continue_remaining, video.remainingMinutes),
                    palette.provider { it.onSurfaceVariant },
                    11.sp,
                )
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        WButton(
            text = context.getString(
                R.string.widget_continue_resume,
                formatClock(video.progress),
            ),
            icon = R.drawable.widget_ic_play,
            background = palette.provider { it.primary },
            foreground = palette.provider { it.onPrimary },
            action = WidgetActions.resume(context, video),
        )
    }
}

@androidx.compose.runtime.Composable
private fun ContinueCover(
    cover: Bitmap?,
    palette: WidgetPalette,
    modifier: GlanceModifier,
) {
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            modifier = modifier.cornerRadius(16.dp),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier
                .cornerRadius(16.dp)
                .background(palette.provider { it.surfaceContainerHigh })
        ) {}
    }
}

private fun continueMeta(context: Context, video: WidgetVideo): String =
    if (video.remainingMinutes > 0) {
        context.getString(
            R.string.widget_continue_meta,
            video.upName,
            video.remainingMinutes,
        )
    } else {
        video.upName
    }
