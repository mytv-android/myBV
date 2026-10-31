package com.github.mytv.bv.viewmodel.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideo
import dev.aaa1115910.biliapi.http.entity.AuthFailureException
import dev.aaa1115910.biliapi.http.entity.RiskControlException
import dev.aaa1115910.biliapi.repositories.UserRepository
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.BuildConfig
import com.github.mytv.bv.R
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.addAllWithMainContext
import com.github.mytv.bv.util.fInfo
import com.github.mytv.bv.util.fWarn
import com.github.mytv.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel
import com.github.mytv.bv.repository.UserRepository as BvUserRepository

@KoinViewModel
class DynamicViewModel(
    private val bvUserRepository: BvUserRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger {}

        /** 动态分页接口失败后的总尝试次数（含首次）。 */
        private const val DYNAMIC_PAGE_MAX_ATTEMPTS = 2

        /** 两次动态分页请求之间的间隔：-352 多半是突发限流，缓一下再试就有了。 */
        private const val DYNAMIC_PAGE_RETRY_DELAY_MS = 1000L
    }

    val dynamicVideoList = mutableStateListOf<DynamicVideo>()
    val dynamicAllList = mutableStateListOf<DynamicItem>()

    private var currentVideoPage = 0
    var loadingVideo = false
    var videoHasMore = true
    private var videoHistoryOffset: String? = null
    private var videoUpdateBaseline: String? = null

    private var currentAllPage = 0
    var loadingAll by mutableStateOf(false)
    var allHasMore by mutableStateOf(true)
    /** 最近一次「全部动态」加载的失败信息；成功后清空。列表页据此区分「加载失败」和「真的没有动态」。 */
    var allError by mutableStateOf<String?>(null)
        private set
    private var allHistoryOffset: String? = null
    private var allUpdateBaseline: String? = null

    /** 按 UP 主筛选时的动态列表（走空间动态接口，不在全部动态里筛）。 */
    val dynamicSpaceList = mutableStateListOf<DynamicItem>()
    private var currentSpacePage = 0
    var loadingSpace by mutableStateOf(false)
    var spaceHasMore by mutableStateOf(true)
    /** 最近一次 UP 主动态加载的失败信息。 */
    var spaceError by mutableStateOf<String?>(null)
        private set
    private var spaceHistoryOffset: String? = null
    /** 当前 dynamicSpaceList 对应的 UP，换了 UP 要重新拉。 */
    private var spaceHostMid: Long? = null

    val isLogin get() = bvUserRepository.isLogin

    init {
        println("=====init DynamicViewModel")
    }

    suspend fun loadMoreVideo() {
        if (!loadingVideo) loadVideoData()
    }

    suspend fun loadMoreAll() {
        if (!loadingAll) loadAllData()
    }

    /**
     * 加载用户[hostMid]的动态。换了 UP 会先清掉上一份，避免两个 UP 的动态混在一列里。
     */
    suspend fun loadMoreSpace(hostMid: Long) {
        if (loadingSpace) return
        if (spaceHostMid != null && spaceHostMid != hostMid) clearSpaceData()
        spaceHostMid = hostMid
        if (!spaceHasMore || !bvUserRepository.isLogin) return
        loadingSpace = true
        logger.fInfo { "Load more space dynamics [mid=$hostMid, offset=$spaceHistoryOffset, page=${currentSpacePage + 1}]" }
        val nextPage = currentSpacePage + 1
        try {
            runCatching {
                val dynamicData = withDynamicPageRetry(hostMid) {
                    userRepository.getSpaceDynamics(
                        hostMid = hostMid,
                        page = nextPage,
                        offset = spaceHistoryOffset ?: ""
                    )
                }
                // 请求期间换了 UP：这份数据是上一个 UP 的，别混进新 UP 的列表里
                if (spaceHostMid != hostMid) return
                currentSpacePage = nextPage
                dynamicSpaceList.addDistinct(dynamicData.dynamics)
                spaceHistoryOffset = dynamicData.historyOffset
                spaceHasMore = dynamicData.hasMore
                spaceError = null
                logger.fInfo { "Load space dynamics page: $nextPage, size: ${dynamicData.dynamics.size}" }
            }.onFailure {
                if (it is CancellationException) throw it
                // 请求期间换了 UP：这一页的成败已经无所谓，别拿上一个 UP 的错误打扰用户
                if (spaceHostMid != hostMid) return@onFailure
                logger.fWarn { "Load space dynamics failed: ${it.stackTraceToString()}" }
                val message = when (it) {
                    is AuthFailureException -> BVApp.context.getString(R.string.exception_auth_failure)
                    is RiskControlException -> "动态被风控拦截，请稍后重试"
                    else -> "加载动态失败: ${it.localizedMessage ?: it.javaClass.simpleName}"
                }
                spaceError = message
                withContext(Dispatchers.Main) {
                    message.toast(BVApp.context)
                }
            }
        } finally {
            loadingSpace = false
        }
    }

    /**
     * 执行一次动态分页请求，失败后重试一次，仍失败才抛出最后一次的异常。
     *
     * 动态接口（feed/all、feed/space）偶发 -352 风控或超时，而页面上的自动加载
     * （DynamicPageState.autoFill）失败后往往又会立刻重试，第二次常常就成功了。
     * 第一次失败就弹提示的话，用户看到的就是「动态已经在眼前，却提示加载动态失败」。
     *
     * @param hostMid 按 UP 拉空间动态时的 UP；重试前换了 UP 就不再拉这一页
     */
    private suspend fun <T> withDynamicPageRetry(hostMid: Long? = null, request: suspend () -> T): T {
        var lastError: Throwable? = null
        for (attempt in 1..DYNAMIC_PAGE_MAX_ATTEMPTS) {
            try {
                return request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                logger.fInfo {
                    "Dynamic page request failed [attempt=$attempt/$DYNAMIC_PAGE_MAX_ATTEMPTS]: ${e.message}"
                }
                // 登录态问题重试没有意义，直接失败
                if (e is AuthFailureException) break
                // 重试前换了 UP：这一页属于上一个 UP
                if (hostMid != null && spaceHostMid != hostMid) break
            }
            if (attempt < DYNAMIC_PAGE_MAX_ATTEMPTS) delay(DYNAMIC_PAGE_RETRY_DELAY_MS)
        }
        throw lastError ?: IllegalStateException("dynamic page request failed")
    }

    private suspend fun loadVideoData() {
        if (!videoHasMore || !bvUserRepository.isLogin) return
        loadingVideo = true
        logger.fInfo { "Load more dynamic videos [apiType=${Prefs.apiType}, offset=$videoHistoryOffset, page=${currentVideoPage + 1}]" }
        val nextPage = currentVideoPage + 1
        try {
            runCatching {
                val dynamicVideoData = userRepository.getDynamicVideos(
                    page = nextPage,
                    offset = videoHistoryOffset ?: "",
                    updateBaseline = videoUpdateBaseline ?: "",
                    preferApiType = Prefs.apiType
                )
                currentVideoPage = nextPage
                dynamicVideoList.addAllWithMainContext(dynamicVideoData.videos)
                videoHistoryOffset = dynamicVideoData.historyOffset
                videoUpdateBaseline = dynamicVideoData.updateBaseline
                videoHasMore = dynamicVideoData.hasMore

                logger.fInfo { "Load dynamic video list page: ${currentVideoPage},size: ${dynamicVideoData.videos.size}" }
                val avList = dynamicVideoData.videos.map {
                    it.aid
                }
                logger.fInfo { "Load dynamic video size: ${avList.size}" }
                logger.info { "Load dynamic video list ${avList}}" }
            }.onFailure {
                // 取消不是失败，别拿它弹错误提示
                if (it is CancellationException) throw it
                logger.fWarn { "Load dynamic video list failed: ${it.stackTraceToString()}" }
                when (it) {
                    is AuthFailureException -> {
                        withContext(Dispatchers.Main) {
                            BVApp.context.getString(R.string.exception_auth_failure)
                                .toast(BVApp.context)
                        }
                        logger.fInfo { "User auth failure" }
                        if (!BuildConfig.DEBUG) bvUserRepository.logout()
                    }

                    is RiskControlException -> {
                        withContext(Dispatchers.Main) {
                            "动态被风控拦截，请稍后重试".toast(BVApp.context)
                        }
                    }

                    else -> {
                        withContext(Dispatchers.Main) {
                            "加载动态失败: ${it.localizedMessage}".toast(BVApp.context)
                        }
                    }
                }
            }
        } finally {
            // 不管成功失败还是被取消，loading 都要落下：停在 true 会让页面永远转圈
            loadingVideo = false
        }
    }

    private suspend fun loadAllData() {
        if (!allHasMore || !bvUserRepository.isLogin) return
        loadingAll = true
        logger.fInfo { "Load more dynamic all [apiType=${Prefs.apiType}, offset=$allHistoryOffset, page=${currentAllPage + 1}]" }
        val nextPage = currentAllPage + 1
        try {
            runCatching {
                val dynamicData = withDynamicPageRetry {
                    userRepository.getDynamics(
                        page = nextPage,
                        offset = allHistoryOffset ?: "",
                        updateBaseline = allUpdateBaseline ?: "",
                        preferApiType = Prefs.apiType
                    )
                }
                currentAllPage = nextPage
                dynamicAllList.addDistinct(dynamicData.dynamics)
                allHistoryOffset = dynamicData.historyOffset
                allUpdateBaseline = dynamicData.updateBaseline
                allHasMore = dynamicData.hasMore
                allError = null

                logger.fInfo { "Load dynamic all list page: ${currentAllPage},size: ${dynamicData.dynamics.size}" }
            }.onFailure {
                if (it is CancellationException) throw it
                logger.fWarn { "Load dynamic all list failed: ${it.stackTraceToString()}" }
                val message = when (it) {
                    is AuthFailureException -> BVApp.context.getString(R.string.exception_auth_failure)
                    is RiskControlException -> "动态被风控拦截，请稍后重试"
                    else -> "加载动态失败: ${it.localizedMessage ?: it.javaClass.simpleName}"
                }
                allError = message
                if (it is AuthFailureException) logger.fInfo { "User auth failure" }
                withContext(Dispatchers.Main) {
                    message.toast(BVApp.context)
                }
            }
        } finally {
            loadingAll = false
        }
    }

    fun clearVideoData() {
        dynamicVideoList.clear()
        currentVideoPage = 0
        loadingVideo = false
        videoHasMore = true
        videoHistoryOffset = null
    }

    fun clearAllData() {
        dynamicAllList.clear()
        currentAllPage = 0
        loadingAll = false
        allHasMore = true
        allError = null
        allHistoryOffset = null
    }

    fun clearSpaceData() {
        dynamicSpaceList.clear()
        currentSpacePage = 0
        loadingSpace = false
        spaceHasMore = true
        spaceError = null
        spaceHistoryOffset = null
        spaceHostMid = null
    }
}

/**
 * 把一页动态并进列表，按动态 id 去重。
 *
 * 重试时服务端可能已经收下上一次请求，同一页会再来一遍；列表里出现重复 key 会让
 * LazyColumn 直接崩（「Key was already used」）。没有 id 的动态去不了重，照原样收下。
 */
private fun MutableList<DynamicItem>.addDistinct(items: List<DynamicItem>) {
    if (items.isEmpty()) return
    val existing = mapNotNullTo(HashSet<String>()) { it.id }
    addAll(items.filter { it.id == null || it.id !in existing })
}