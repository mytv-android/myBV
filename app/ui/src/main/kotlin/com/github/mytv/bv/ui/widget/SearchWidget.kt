package com.github.mytv.bv.ui.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.github.mytv.bv.appui.R

internal fun searchCompact(size: DpSize): Boolean = size.width < 240.dp

class SearchWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 40.dp), // 老启动器的 2×1
            DpSize(160.dp, 40.dp),
            DpSize(160.dp, 84.dp),
            DpSize(250.dp, 40.dp), // 老启动器的 4×1
            DpSize(328.dp, 40.dp),
            DpSize(328.dp, 84.dp),
            DpSize(412.dp, 40.dp),
            DpSize(412.dp, 84.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.readSearch(context)
        if (snapshot == null || !isFresh(snapshot.fetchedAt, TTL_SEARCH)) {
            WidgetRefresher.request(context)
        }
        val palette = widgetPalette(context)
        provideContent {
            GlanceTheme(colors = palette.glanceColors()) {
                SearchContent(snapshot, palette)
            }
        }
    }
}

class SearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SearchWidget()
}

@androidx.compose.runtime.Composable
private fun SearchContent(
    snapshot: SearchSnapshot?,
    palette: WidgetPalette,
) {
    val context = LocalContext.current
    val hotword = snapshot?.hotword.orEmpty()
    val compact = searchCompact(LocalSize.current)
    val tight = LocalSize.current.height < 70.dp
    val verticalPadding = if (tight) 4.dp else 14.dp
    val logoSize = if (tight) 32.dp else 40.dp
    val iconSize = if (tight) 18.dp else 20.dp
    val buttonSize = if (tight) 32.dp else 40.dp
    val pillHeight = (LocalSize.current.height - verticalPadding * 2).coerceAtLeast(1.dp)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(vertical = verticalPadding)
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(palette.provider { it.surfaceContainerHigh })
                .cornerRadius(minOf(28.dp, pillHeight / 2))
                .padding(horizontal = if (tight) 6.dp else 8.dp)
                .clickable(WidgetActions.search(context, hotword)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(logoSize)
                    .background(palette.provider { it.primaryContainer })
                    .cornerRadius(logoSize / 2),
                contentAlignment = Alignment.Center,
            ) {
                WIcon(
                    R.drawable.widget_ic_brand,
                    palette.provider { it.onPrimaryContainer },
                    if (tight) 20.dp else 24.dp,
                )
            }
            if (compact) {
                Spacer(GlanceModifier.defaultWeight())
                WIcon(
                    R.drawable.widget_ic_search,
                    palette.provider { it.onSurfaceVariant },
                    iconSize,
                )
            } else {
                Spacer(GlanceModifier.width(if (tight) 8.dp else 10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    if (!tight) {
                        WText(
                            context.getString(R.string.widget_search_hot),
                            palette.provider { it.primary },
                            10.5.sp,
                            FontWeight.Bold,
                        )
                    }
                    WText(
                        hotword.ifBlank { context.getString(R.string.widget_search_placeholder) },
                        palette.provider { it.onSurface },
                        if (tight) 13.sp else 15.sp,
                        maxLines = 1,
                    )
                }
                WIcon(
                    R.drawable.widget_ic_search,
                    palette.provider { it.onSurfaceVariant },
                    iconSize,
                )
                Spacer(GlanceModifier.width(if (tight) 6.dp else 8.dp))
                Box(
                    modifier = GlanceModifier
                        .size(buttonSize)
                        .background(palette.provider { it.secondaryContainer })
                        .cornerRadius(buttonSize / 2)
                        .clickable(WidgetActions.shorts(context)),
                    contentAlignment = Alignment.Center,
                ) {
                    WIcon(
                        R.drawable.widget_ic_shorts,
                        palette.provider { it.onSecondaryContainer },
                        if (tight) 18.dp else 22.dp,
                    )
                }
            }
        }
    }
}
