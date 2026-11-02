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
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.github.mytv.bv.appui.R

class ShortsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp), // 老启动器的 2×2
            DpSize(160.dp, 176.dp),
            DpSize(160.dp, 268.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readShorts(context)
        if (snapshot?.item == null || !isFresh(snapshot.fetchedAt, TTL_SHORTS)) {
            WidgetRefresher.request(context)
        }
        val item = snapshot?.item
        val palette = widgetPalette(
            context,
            coverUrl = item?.cover,
            forceDark = true,
            alwaysCoverSeed = true,
        )
        // 按最高的一档裁背景，2×2 / 2×3 都由 ContentScale.Crop 适配
        val background = item?.cover?.takeIf { it.isNotBlank() }
            ?.let { WidgetImages.shortsCover(context, it, 160f, 268f, 28f) }
        val resolved = palette.resolved(context)
        val fab = WidgetImages.shape(context, 40f, WidgetShape.Sunny, resolved.primary.toArgb())
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                ShortsContent(snapshot, palette, background, fab)
            }
        }
    }
}

class ShortsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortsWidget()
}

@androidx.compose.runtime.Composable
private fun ShortsContent(
    snapshot: ShortsSnapshot?,
    palette: WidgetPalette,
    background: Bitmap?,
    fab: Bitmap,
) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < 150.dp
    val item = snapshot?.item
    val action = WidgetActions.shorts(context, item?.aid)
    val root = GlanceModifier.fillMaxSize()
    val withBackground = if (background != null) {
        root.background(ImageProvider(background), ContentScale.Crop)
    } else {
        root.background(ColorProvider(Color.Black))
    }
    Column(
        modifier = withBackground
            .cornerRadius(widgetCornerRadius(context))
            .padding(12.dp)
            .clickable(action),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WOverlayChip(
                context.getString(R.string.widget_shorts_chip),
                icon = R.drawable.widget_ic_shorts,
            )
            Spacer(GlanceModifier.defaultWeight())
            WFab(
                shape = fab,
                icon = R.drawable.widget_ic_play,
                tint = palette.provider { it.onPrimary },
                action = action,
                size = if (compact) 34.dp else 40.dp,
                iconSize = if (compact) 18.dp else 20.dp,
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        WText(
            item?.title ?: context.getString(R.string.widget_shorts_empty),
            ColorProvider(Color.White),
            13.sp,
            FontWeight.Bold,
            maxLines = if (compact) 1 else 2,
        )
        Spacer(GlanceModifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WIcon(
                R.drawable.widget_ic_heart,
                ColorProvider(Color(0xD1FFFFFF)),
                13.dp,
            )
            Spacer(GlanceModifier.width(4.dp))
            WText(
                listOf(snapshot?.likeText, item?.upName)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                ColorProvider(Color(0xD1FFFFFF)),
                11.sp,
                maxLines = 1,
            )
        }
    }
}
