package com.github.mytv.bv.ui.feature.login

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.github.mytv.bv.entity.BvScheme
import com.github.mytv.bv.ui.navigation.DynamicRoute
import com.github.mytv.bv.ui.navigation.LoginRoute
import com.github.mytv.bv.ui.navigation.PgcTimelineRoute
import com.github.mytv.bv.ui.navigation.PlayerRoute
import com.github.mytv.bv.ui.navigation.SearchRoute
import com.github.mytv.bv.ui.navigation.SeasonRoute
import com.github.mytv.bv.ui.navigation.ShortsRoute
import com.github.mytv.bv.ui.navigation.ToViewRoute
import com.github.mytv.bv.ui.navigation.TopLevel
import com.github.mytv.bv.ui.navigation.VideoRoute
import com.github.mytv.bv.ui.navigation.navigateTopLevel
import com.github.mytv.bv.ui.widget.WidgetRefresher
import com.github.mytv.bv.ui.widget.WidgetStore

/**
 * 处理外部深链：bugvideo://qrtoken 导入账号，bugvideo://video/{aid} 打开视频，
 * bugvideo://recommend 回首页，bugvideo://toview 打开稍后再看。
 * 每个 Intent 只处理一次：按 Intent 对象去重。电视桌面推荐的同一张卡可以被反复点击，
 * launcher 每次送来数据相同、对象不同的 Intent；按 URI 文本去重会把第二次点击误判为已处理。
 */
@Composable
fun DeepLinkEffect(intent: Intent?, nav: NavController) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf<Intent?>(null) }
    LaunchedEffect(intent) {
        if (intent == null || intent === handled) return@LaunchedEffect
        val uri = intent.data ?: return@LaunchedEffect
        handled = intent

        when (uri.host) {
            BvScheme.Video.HOST -> {
                uri.pathSegments.firstOrNull()?.toLongOrNull()
                    ?.let { nav.navigate(VideoRoute(it)) { launchSingleTop = true } }
            }

            BvScheme.Recommend.host -> {
                nav.navigateTopLevel(TopLevel.Home)
            }

            BvScheme.ToView.host -> {
                nav.navigate(ToViewRoute) { launchSingleTop = true }
            }

            BvScheme.Shorts.host -> {
                val aid = uri.getQueryParameter("aid")?.toLongOrNull()
                if (aid != null) {
                    WidgetStore.markShortsSeen(context, aid)
                    WidgetRefresher.requestShorts(context)
                }
                nav.navigate(ShortsRoute(aid)) { launchSingleTop = true }
            }

            BvScheme.Dynamic.host -> {
                WidgetStore.markDynamicSeen(context)
                WidgetRefresher.updateWidgetsSoon(context)
                nav.navigate(DynamicRoute) { launchSingleTop = true }
            }

            BvScheme.Search.host -> {
                nav.navigate(SearchRoute(uri.getQueryParameter("q").orEmpty())) {
                    launchSingleTop = true
                }
            }

            BvScheme.Login.host -> {
                nav.navigate(LoginRoute) { launchSingleTop = true }
            }

            BvScheme.Timeline.host -> {
                nav.navigate(
                    PgcTimelineRoute(uri.getQueryParameter("day")?.toIntOrNull())
                ) { launchSingleTop = true }
            }

            BvScheme.Player.host -> {
                val aid = uri.getQueryParameter("aid")?.toLongOrNull()
                if (aid != null) {
                    nav.navigate(
                        PlayerRoute(
                            aid = aid,
                            cid = uri.getQueryParameter("cid")?.toLongOrNull() ?: 0L,
                            title = uri.getQueryParameter("title").orEmpty(),
                            played = uri.getQueryParameter("played")?.toIntOrNull() ?: 0,
                            fromSeason = uri.getQueryParameter("fromSeason") == "1",
                            epid = uri.getQueryParameter("epid")?.toIntOrNull() ?: 0,
                            seasonId = uri.getQueryParameter("seasonId")?.toIntOrNull() ?: 0,
                        )
                    ) { launchSingleTop = true }
                }
            }

            BvScheme.Season.host -> {
                val seasonId = uri.getQueryParameter("seasonId")?.toIntOrNull()?.takeIf { it > 0 }
                val epId = uri.getQueryParameter("epId")?.toIntOrNull()?.takeIf { it > 0 }
                // Web 端历史可能只有 epId：SeasonScreen 会按 epId 反查季度
                if (seasonId != null || epId != null) {
                    nav.navigate(SeasonRoute(seasonId = seasonId, epId = epId)) {
                        launchSingleTop = true
                    }
                }
            }

            BvScheme.QrToken.HOST -> {
                pending = uri.toString()
            }
        }
    }
    pending?.let { text ->
        QrTokenImportDialog(uri = Uri.parse(text), onDismiss = { pending = null })
    }
}
