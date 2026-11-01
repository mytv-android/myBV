package com.github.mytv.bv.viewmodel.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.http.entity.RiskControlException
import dev.aaa1115910.biliapi.repositories.SearchFilterDuration
import dev.aaa1115910.biliapi.repositories.SearchFilterOrderType
import dev.aaa1115910.biliapi.repositories.SearchRepository
import dev.aaa1115910.biliapi.repositories.SearchType
import dev.aaa1115910.biliapi.repositories.SearchTypePage
import dev.aaa1115910.biliapi.repositories.SearchTypeResult
import com.github.mytv.bv.R
import com.github.mytv.bv.util.Partition
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.fException
import com.github.mytv.bv.util.fInfo
import com.github.mytv.bv.util.fWarn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class SearchResultViewModel(
    private val searchRepository: SearchRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }

        /** 风控降级响应的最多尝试次数与重试间隔（隔一会儿再请求通常就能拿到结果）。 */
        private const val RiskControlAttempts = 3
        private const val RiskControlRetryDelayMs = 1000L
    }

    var keyword by mutableStateOf("")
    var searchType by mutableStateOf(SearchType.Video)

    var videoSearchResult by mutableStateOf(SearchResult(SearchType.Video))
    var mediaBangumiSearchResult by mutableStateOf(SearchResult(SearchType.MediaBangumi))
    var mediaFtSearchResult by mutableStateOf(SearchResult(SearchType.MediaFt))
    var biliUserSearchResult by mutableStateOf(SearchResult(SearchType.BiliUser))

    var selectedOrder by mutableStateOf(SearchFilterOrderType.MostClicks)
    var selectedDuration by mutableStateOf(SearchFilterDuration.All)
    var selectedPartition: Partition? by mutableStateOf(null)
    var selectedChildPartition: Partition? by mutableStateOf(null)

    private var updating = false
    val hasMore = true

    /** 每次 [update] 自增：关键词 / 筛选换掉后，旧批次回来的响应要丢弃。 */
    @Volatile
    private var generation = 0

    var enableProxySearchResult = false

    fun update() {
        generation++
        resetPages()
        clearResults()
        SearchType.entries.forEach { loadMore(it, true) }
    }

    private fun resetPages() {
        videoSearchResult.resetPage()
        mediaBangumiSearchResult.resetPage()
        mediaFtSearchResult.resetPage()
        biliUserSearchResult.resetPage()
    }

    private fun clearResults() {
        videoSearchResult.clearResult()
        mediaBangumiSearchResult.clearResult()
        mediaFtSearchResult.clearResult()
        biliUserSearchResult.clearResult()
    }

    fun loadMore(
        searchType: SearchType,
        ignoreUpdating: Boolean = false
    ) {
        if (!hasMore) return
        if (updating && !ignoreUpdating) return

        // 请求参数在发起时固定：期间用户换了关键词 / 筛选，旧响应回来要丢弃，
        // 否则会混进新关键词的结果里（协程真正开始时 keyword 可能已经变了）
        val requestGeneration = generation
        val requestKeyword = keyword
        val page = when (searchType) {
            SearchType.Video -> videoSearchResult.page
            SearchType.MediaBangumi -> mediaBangumiSearchResult.page
            SearchType.MediaFt -> mediaFtSearchResult.page
            SearchType.BiliUser -> biliUserSearchResult.page
        }
        val tid = selectedChildPartition?.tid ?: selectedPartition?.tid
        val order = selectedOrder
        val duration = selectedDuration

        updating = true
        viewModelScope.launch(Dispatchers.IO) {
            logger.fInfo { "Load search result: [keyword=$requestKeyword, type=$searchType, page=$page]" }
            runCatching {
                val searchResultResponse = withRiskControlRetry(
                    // 关键词 / 筛选已经换掉就别再替旧请求重试，免得继续压风控
                    shouldRetry = { requestGeneration == generation }
                ) {
                    searchRepository.searchType(
                        keyword = requestKeyword,
                        type = searchType,
                        page = page,
                        tid = tid,
                        order = order,
                        duration = duration,
                        preferApiType = Prefs.apiType,
                        enableProxy = enableProxySearchResult
                    )
                }
                withContext(Dispatchers.Main) {
                    if (requestGeneration != generation) return@withContext
                    when (searchType) {
                        SearchType.Video -> {
                            videoSearchResult =
                                videoSearchResult.appendSearchResultData(searchResultResponse)
                            videoSearchResult.page = searchResultResponse.page
                        }

                        SearchType.MediaBangumi -> {
                            mediaBangumiSearchResult =
                                mediaBangumiSearchResult.appendSearchResultData(searchResultResponse)
                            mediaBangumiSearchResult.page = searchResultResponse.page
                        }

                        SearchType.MediaFt -> {
                            mediaFtSearchResult =
                                mediaFtSearchResult.appendSearchResultData(searchResultResponse)
                            mediaFtSearchResult.page = searchResultResponse.page
                        }

                        SearchType.BiliUser -> {
                            biliUserSearchResult =
                                biliUserSearchResult.appendSearchResultData(searchResultResponse)
                            biliUserSearchResult.page = searchResultResponse.page
                        }
                    }
                }
            }.onFailure {
                logger.fException(it) { "Load search result failed [searchType=$searchType, keyword=$requestKeyword, page=$page]" }
            }
            updating = false
        }
    }

    /**
     * B 站会把短时间内的重复搜索当成风控，返回 code=0、data 里只有 v_voucher 的
     * 降级响应；隔一会儿再请求通常就能拿到结果。
     */
    private suspend fun <T> withRiskControlRetry(
        shouldRetry: () -> Boolean = { true },
        request: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return request()
            } catch (e: RiskControlException) {
                if (attempt >= RiskControlAttempts || !shouldRetry()) throw e
                attempt++
                logger.fWarn { "Search request risk controlled, retrying [attempt=$attempt/$RiskControlAttempts]" }
                delay(RiskControlRetryDelayMs)
            }
        }
    }

    data class SearchResult(
        var type: SearchType,
        var videos: List<SearchTypeResult.Video> = emptyList(),
        var mediaBangumis: List<SearchTypeResult.Pgc> = emptyList(),
        var mediaFts: List<SearchTypeResult.Pgc> = emptyList(),
        var biliUsers: List<SearchTypeResult.User> = emptyList(),
        var page: SearchTypePage = SearchTypePage()
    ) {
        val count get() = videos.size + mediaBangumis.size + mediaFts.size + biliUsers.size

        fun resetPage() {
            page = SearchTypePage()
        }

        fun clearResult() {
            videos = emptyList()
            mediaBangumis = emptyList()
            mediaFts = emptyList()
            biliUsers = emptyList()
        }

        fun appendSearchResultData(searchTypeResult: SearchTypeResult): SearchResult {
            return when (type) {
                SearchType.Video -> {
                    SearchResult(type).apply {
                        this.videos = this@SearchResult.videos + searchTypeResult.videos
                    }
                }

                SearchType.MediaBangumi -> {
                    SearchResult(type).apply {
                        this.mediaBangumis = this@SearchResult.mediaBangumis + searchTypeResult.pgcs
                    }
                }

                SearchType.MediaFt -> {
                    SearchResult(type).apply {
                        this.mediaFts = this@SearchResult.mediaFts + searchTypeResult.pgcs
                    }
                }

                SearchType.BiliUser -> {
                    SearchResult(type).apply {
                        this.biliUsers = this@SearchResult.biliUsers + searchTypeResult.users
                    }
                }
            }
        }
    }
}

enum class SearchResultType(
    val type: String,
    private val strRes: Int
) {
    Video(type = "video", strRes = R.string.search_result_type_name_video),
    MediaBangumi(type = "media_bangumi", R.string.search_result_type_name_media_bangumi),
    MediaFt(type = "media_ft", strRes = R.string.search_result_type_name_media_ft),
    BiliUser(type = "bili_user", strRes = R.string.search_result_type_name_bili_user);

    fun getDisplayName(context: Context) = context.getString(strRes)
}
