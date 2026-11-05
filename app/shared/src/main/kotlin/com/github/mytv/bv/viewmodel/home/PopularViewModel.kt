package com.github.mytv.bv.viewmodel.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.aaa1115910.biliapi.entity.rank.PopularVideoPage
import dev.aaa1115910.biliapi.entity.ugc.UgcItem
import dev.aaa1115910.biliapi.repositories.RecommendVideoRepository
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.fError
import com.github.mytv.bv.util.fInfo
import com.github.mytv.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class PopularViewModel(
    private val recommendVideoRepository: RecommendVideoRepository
) : ViewModel() {
    private val logger = KotlinLogging.logger {}
    val popularVideoList = mutableStateListOf<UgcItem>()

    private var nextPage = PopularVideoPage()
    var refreshing by mutableStateOf(false)
    var loading by mutableStateOf(false)

    init {
        println("=====init PopularViewModel")
    }

    suspend fun loadMore(
        beforeAppendData: () -> Unit = {}
    ) {
        if (!loading) loadData(
            beforeAppendData = beforeAppendData
        )
    }

    private suspend fun loadData(
        beforeAppendData: () -> Unit
    ) {
        loading = true
        logger.fInfo { "Load more popular videos" }
        runCatching {
            val popularVideoData = recommendVideoRepository.getPopularVideos(
                page = nextPage,
                preferApiType = Prefs.apiType
            )
            // 刷新时 beforeAppendData 会清空列表：清空与回填必须在同一主线程块内原子完成，
            // 否则中间的空列表帧会销毁 TV 上持有焦点的内容节点，焦点漂到导航栏
            withContext(Dispatchers.Main) {
                beforeAppendData()
                nextPage = popularVideoData.nextPage
                popularVideoList.addAll(popularVideoData.list)
            }
        }.onFailure {
            logger.fError { "Load popular video list failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "加载热门视频失败: ${it.localizedMessage}".toast(BVApp.context)
            }
        }
        loading = false
    }

    fun clearData() {
        popularVideoList.clear()
        resetPage()
        loading = false
    }

    fun resetPage() {
        nextPage = PopularVideoPage()
        refreshing = true
    }
}