package com.github.mytv.bv.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.github.mytv.bv.appui.R
import kotlin.reflect.KClass

/** 六个一级入口（顺序固定）：首页 · 刷刷 · 影视 · 直播 · 动态 · 我的。 */
enum class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val icon: @Composable () -> Painter,
    val iconSelected: @Composable () -> Painter,
) {
    Home(HomeRoute, HomeRoute::class, R.string.nav_home, { painterResource(R.drawable.ic_home_outlined_24) }, { painterResource(R.drawable.ic_home_24) }),
    Shorts(ShortsRoute(), ShortsRoute::class, R.string.nav_shorts, { painterResource(R.drawable.ic_shorts_24) }, { painterResource(R.drawable.ic_shorts_filled_24) }),
    Pgc(PgcRoute, PgcRoute::class, R.string.nav_pgc, { painterResource(R.drawable.ic_movie_outlined_24) }, { painterResource(R.drawable.ic_movie_24) }),
    Live(LiveRoute, LiveRoute::class, R.string.nav_live, { painterResource(R.drawable.ic_live_tv_outlined_24) }, { painterResource(R.drawable.ic_live_tv_24) }),
    Dynamic(DynamicRoute, DynamicRoute::class, R.string.nav_dynamic, { painterResource(R.drawable.ic_subscriptions_outlined_24) }, { painterResource(R.drawable.ic_subscriptions_24) }),
    Me(MeRoute, MeRoute::class, R.string.nav_me, { painterResource(R.drawable.ic_person_outlined_24) }, { painterResource(R.drawable.ic_person_24) });

    companion object {
        fun of(destination: NavDestination?): TopLevel? =
            destination?.let { dest -> entries.firstOrNull { dest.hasRoute(it.routeClass) } }
    }
}
