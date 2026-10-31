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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import com.github.mytv.bv.appui.R

class DynamicWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp), // 老启动器的 2×2
            DpSize(160.dp, 176.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readDynamic(context)
        if (snapshot == null || !isFresh(snapshot.fetchedAt, TTL_DYNAMIC)) {
            WidgetRefresher.request(context)
        }
        val palette = widgetPalette(context)
        val resolved = palette.resolved(context)
        val avatars = snapshot?.avatars.orEmpty().take(3)
        val strip = if (avatars.isNotEmpty()) {
            WidgetImages.avatarStrip(
                context = context,
                urls = avatars,
                backgroundColor = resolved.secondaryContainer.toArgb(),
            )
        } else {
            null
        }
        val blob = WidgetImages.shape(context, 56f, WidgetShape.Cookie, resolved.tertiaryContainer.toArgb())
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                DynamicContent(snapshot, palette, strip, avatars.size, blob)
            }
        }
    }
}

class DynamicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DynamicWidget()
}

@androidx.compose.runtime.Composable
private fun DynamicContent(
    snapshot: DynamicSnapshot?,
    palette: WidgetPalette,
    strip: Bitmap?,
    avatarCount: Int,
    blob: Bitmap,
) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < 150.dp
    WRoot(
        background = palette.provider { it.secondaryContainer },
        corner = widgetCornerRadius(context),
        modifier = GlanceModifier.clickable(WidgetActions.dynamic(context)),
    ) {
        when {
            snapshot == null -> Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WText(
                    context.getString(R.string.widget_loading),
                    palette.provider { it.onSecondaryContainer },
                    12.sp,
                )
            }

            !snapshot.loggedIn -> Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(if (compact) 36.dp else 56.dp)
                        .background(ImageProvider(blob), ContentScale.FillBounds),
                    contentAlignment = Alignment.Center,
                ) {
                    WIcon(
                        R.drawable.widget_ic_person,
                        palette.provider { it.onTertiaryContainer },
                        if (compact) 18.dp else 26.dp,
                    )
                }
                if (!compact) {
                    Spacer(GlanceModifier.height(6.dp))
                    WText(
                        context.getString(R.string.widget_login_dynamic_title),
                        palette.provider { it.onSecondaryContainer },
                        13.sp,
                        FontWeight.Bold,
                        maxLines = 2,
                        align = TextAlign.Center,
                    )
                }
                Spacer(GlanceModifier.height(if (compact) 4.dp else 8.dp))
                WButton(
                    text = context.getString(R.string.widget_login),
                    icon = R.drawable.widget_ic_person,
                    background = palette.provider { it.primary },
                    foreground = palette.provider { it.onPrimary },
                    action = WidgetActions.login(context),
                    height = if (compact) 30.dp else 36.dp,
                )
            }

            else -> {
                if (!compact) {
                    if (strip != null) {
                        Image(
                            provider = ImageProvider(strip),
                            contentDescription = null,
                            modifier = GlanceModifier
                                .width((38f + 28f * (avatarCount - 1)).dp)
                                .height(38.dp),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Box(
                            modifier = GlanceModifier
                                .width(38.dp)
                                .height(38.dp)
                                .background(ImageProvider(blob), ContentScale.FillBounds),
                            contentAlignment = Alignment.Center,
                        ) {
                            WIcon(
                                R.drawable.widget_ic_person,
                                palette.provider { it.onTertiaryContainer },
                                20.dp,
                            )
                        }
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                if (snapshot.newCount > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        WText(
                            if (snapshot.newCount > 99) "99+" else snapshot.newCount.toString(),
                            palette.provider { it.onSecondaryContainer },
                            if (compact) 34.sp else 48.sp,
                            FontWeight.Bold,
                        )
                        Spacer(GlanceModifier.width(if (compact) 4.dp else 6.dp))
                        WText(
                            context.getString(R.string.widget_dynamic_unit),
                            palette.provider { it.onSecondaryContainer },
                            if (compact) 11.sp else 13.sp,
                            FontWeight.Bold,
                        )
                    }
                } else {
                    WText(
                        context.getString(R.string.widget_dynamic_zero),
                        palette.provider { it.onSecondaryContainer },
                        if (compact) 11.sp else 13.sp,
                        FontWeight.Bold,
                    )
                }
                if (!compact) {
                    Spacer(GlanceModifier.height(6.dp))
                    WText(
                        snapshot.latest,
                        palette.provider { it.onSecondaryContainer.copy(alpha = .86f) },
                        11.5.sp,
                        FontWeight.Normal,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
