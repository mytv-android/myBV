package com.github.mytv.bv.ui.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.adaptive.FoldPanes
import com.github.mytv.bv.ui.designsystem.component.BvSearchField
import com.github.mytv.bv.ui.designsystem.component.BvTabRow
import com.github.mytv.bv.ui.designsystem.component.LoadMoreIndicator
import com.github.mytv.bv.ui.designsystem.component.SectionHeader
import com.github.mytv.bv.ui.navigation.HistoryRoute
import com.github.mytv.bv.ui.navigation.LocalBottomBarPadding
import com.github.mytv.bv.ui.navigation.SearchRoute
import com.github.mytv.bv.ui.navigation.TopLevel
import com.github.mytv.bv.ui.navigation.navigateTopLevel
import com.github.mytv.bv.ui.navigation.openVideo

/**
 * 首页的折叠分区布局（设计稿 home / book、home / dual）。
 *
 * 左侧逻辑页：头像 + 搜索、标签页、今日精选、继续观看与分区快捷入口；
 * 右侧逻辑页：当前标签页的推荐流。两侧独立滚动，[FoldPanes.hinge] 区域一律留空。
 * 导航轨由外壳提供，已计入 [FoldPanes] 的左侧宽度。
 */
@Composable
internal fun HomeFold(
    nav: NavController,
    state: HomeState,
    panes: FoldPanes,
    page: Int,
    onPage: (Int) -> Unit,
    region: Int,
    onRegion: (Int) -> Unit,
    recommendGrid: LazyGridState,
    popularGrid: LazyGridState,
) {
    val names = rememberRegionNames()
    val barPadding = LocalBottomBarPadding.current
    val recommendVm = state.recommend
    val popularVm = state.popular
    val recommendList = recommendVm.recommendVideoList
    val popularList = popularVm.popularVideoList

    LaunchedEffect(Unit) { state.ensureRecommend() }
    LaunchedEffect(Unit) { state.ensurePopular() }
    recommendGrid.LoadMoreEffect { if (recommendList.isNotEmpty()) state.loadMoreRecommend() }
    popularGrid.LoadMoreEffect { if (popularList.isNotEmpty()) state.loadMorePopular() }

    val tabs = listOf(
        stringResource(R.string.home_tab_recommend),
        stringResource(R.string.home_tab_popular),
        stringResource(R.string.home_tab_region),
    )

    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        FoldMainPane(
            nav = nav,
            state = state,
            names = names,
            page = page,
            onPage = onPage,
            region = region,
            onRegion = onRegion,
            tabs = tabs,
            modifier = Modifier
                .width(panes.first)
                .fillMaxHeight(),
        )
        // 折叠边 / 物理铰链：不放内容，也不做点击目标
        Spacer(Modifier.width(panes.hinge))
        Column(
            Modifier
                .width(panes.second)
                .fillMaxHeight()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp + barPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (page) {
                PagePopular -> FoldFeedPane(
                    title = stringResource(R.string.home_tab_popular),
                    list = popularList,
                    loading = popularVm.loading,
                    refreshing = state.popularPulling,
                    onRefresh = state::refreshPopular,
                    onRetry = state::loadMorePopular,
                    gridState = popularGrid,
                    skip = 0,
                    nav = nav,
                )

                PageRegion -> Column(Modifier.fillMaxSize()) {
                    SectionHeader(title = names[region])
                    RegionChipRow(
                        names = names,
                        selected = region,
                        onSelect = onRegion,
                        contentPadding = PaddingValues(vertical = 10.dp),
                    )
                    key(region) {
                        RegionGrid(
                            nav = nav,
                            state = state,
                            type = HomeRegions[region],
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            spacing = 12.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> FoldFeedPane(
                    title = stringResource(R.string.home_for_you),
                    list = recommendList,
                    loading = recommendVm.loading,
                    refreshing = state.recommendPulling,
                    onRefresh = state::refreshRecommend,
                    onRetry = state::loadMoreRecommend,
                    gridState = recommendGrid,
                    skip = state.featured.size,
                    nav = nav,
                )
            }
        }
    }
}

@Composable
private fun FoldMainPane(
    nav: NavController,
    state: HomeState,
    names: List<String>,
    page: Int,
    onPage: (Int) -> Unit,
    region: Int,
    onRegion: (Int) -> Unit,
    tabs: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeAvatar(state, onClick = { nav.navigateTopLevel(TopLevel.Me) })
            BvSearchField(
                value = "",
                placeholder = state.searchPlaceholder(),
                modifier = Modifier.weight(1f),
                onClick = { nav.navigate(SearchRoute()) },
                onVoiceResult = { nav.navigate(SearchRoute(it)) },
            )
        }
        BvTabRow(
            tabs = tabs,
            selected = page,
            onSelect = onPage,
            horizontalPadding = 12.dp,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp + LocalBottomBarPadding.current)
        ) {
            if (state.featured.isNotEmpty()) {
                PhoneFeaturedCarousel(
                    items = state.featured,
                    onOpen = { nav.openVideo(it.toCard()) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            if (state.continueWatching.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.home_continue_watching),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    action = stringResource(R.string.home_all),
                    onAction = { nav.navigate(HistoryRoute) },
                )
                ContinueRow(
                    items = state.continueWatching,
                    cardWidth = 140.dp,
                    spacing = 10.dp,
                    onOpen = { nav.openVideo(it) },
                    modifier = Modifier.padding(top = 10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                )
            }
            SectionHeader(
                title = stringResource(R.string.home_tab_region),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp),
            )
            RegionChipRow(
                names = names,
                selected = if (page == PageRegion) region else -1,
                onSelect = {
                    onRegion(it)
                    onPage(PageRegion)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FoldFeedPane(
    title: String,
    list: List<dev.aaa1115910.biliapi.entity.ugc.UgcItem>,
    loading: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    gridState: LazyGridState,
    skip: Int,
    nav: NavController,
) {
    SectionHeader(title = title)
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (list.isEmpty() && !refreshing) {
            EmptyOrLoading(
                loading = loading,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ugcCards(list, skip = skip, onOpen = { nav.openVideo(it) })
                if (loading) {
                    item(key = "loading") { LoadMoreIndicator() }
                }
            }
        }
    }
}
