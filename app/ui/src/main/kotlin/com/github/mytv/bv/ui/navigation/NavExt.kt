package com.github.mytv.bv.ui.navigation

import androidx.navigation.NavController
import com.github.mytv.bv.entity.carddata.VideoCardData

/** 切换一级页面：保存/恢复各一级页面的状态。 */
fun NavController.navigateTopLevel(top: TopLevel) {
    // 搜索页等非一级页面在栈顶时先正常关闭：popUpTo(saveState) 会把栈顶的搜索页一起
    // 存进被切走的一级页面，之后 restoreState 切回该页面时又会落回搜索页。
    if (TopLevel.of(currentDestination) == null) {
        popBackStack()
    }
    navigate(top.route) {
        popUpTo(HomeRoute) { saveState = true }
        launchSingleTop = true
        // 首页始终留在返回栈底部，状态由它自己的条目保存。恢复首页的已保存栈没有意义：
        // Navigation 会把 popUpTo 目标也映射到被弹出的页面，恢复出来的是别的页面。
        restoreState = top != TopLevel.Home
    }
}

/** 打开视频卡：PGC 内容（jumpToSeason）进番剧详情，其余进视频详情。 */
fun NavController.openVideo(data: VideoCardData) {
    if (data.jumpToSeason && data.epId != null) {
        navigate(SeasonRoute(epId = data.epId))
    } else {
        navigate(VideoRoute(data.avid))
    }
}

fun NavController.openVideo(aid: Long) = navigate(VideoRoute(aid))
