package com.github.mytv.bv.ui.feature.home

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.mytv.bv.ui.designsystem.adaptive.DeviceLayout
import com.github.mytv.bv.ui.designsystem.adaptive.LocalDeviceProfile
import com.github.mytv.bv.ui.designsystem.adaptive.rememberFoldPanes

/**
 * 首页（第 9.1 节，原型 homePhone / homeTablet / homeTv）。
 * ViewModel 在这里创建（rememberHomeState），三种布局共享。
 *
 * 折叠边竖向分隔（书本半开 / 双屏跨屏）且两片都够宽时，改用 [HomeFold]：
 * 导航、精选与继续观看留在主区，推荐流进入相邻区；标签页、分区与滚动位置在
 * 形态变化前后由这里持有的状态保持。
 */
@Composable
fun HomeScreen(nav: NavController) {
    val state = rememberHomeState()
    val layout = LocalDeviceProfile.current.layout
    val fold = rememberFoldPanes()?.takeIf { it.vertical && it.isUsable(300.dp) }
    var page by rememberSaveable { mutableIntStateOf(PageRecommend) }
    var region by rememberSaveable { mutableIntStateOf(0) }
    val recommendGrid = rememberLazyGridState()
    val popularGrid = rememberLazyGridState()

    // 同旧电视版 HomeContent：进入首页时更新用户信息（头像）；手机/平板在登录后加载「继续观看」
    LaunchedEffect(state.user.isLogin, layout) {
        if (state.user.isLogin) state.user.updateUserInfo()
        if (layout != DeviceLayout.Tv) state.ensureHistory()
    }

    when {
        fold != null && layout != DeviceLayout.Tv -> HomeFold(
            nav = nav,
            state = state,
            panes = fold,
            page = page,
            onPage = { page = it },
            region = region,
            onRegion = { region = it },
            recommendGrid = recommendGrid,
            popularGrid = popularGrid,
        )

        layout == DeviceLayout.Phone -> HomePhone(
            nav = nav,
            state = state,
            page = page,
            onPage = { page = it },
            region = region,
            onRegion = { region = it },
            recommendGrid = recommendGrid,
            popularGrid = popularGrid,
        )

        layout == DeviceLayout.Tablet -> HomeTablet(
            nav = nav,
            state = state,
            page = page,
            onPage = { page = it },
            region = region,
            onRegion = { region = it },
            recommendGrid = recommendGrid,
            popularGrid = popularGrid,
        )

        else -> HomeTv(nav, state)
    }
}

/** 首页标签页（推荐 / 热门 / 分区）的共用索引，折叠前后保持。 */
internal const val PageRecommend = 0
internal const val PagePopular = 1
internal const val PageRegion = 2
