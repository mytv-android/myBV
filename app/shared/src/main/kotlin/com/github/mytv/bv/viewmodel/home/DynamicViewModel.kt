package com.github.mytv.bv.viewmodel.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideo
import dev.aaa1115910.biliapi.http.entity.AuthFailureException
import dev.aaa1115910.biliapi.http.entity.RiskControlException
import dev.aaa1115910.biliapi.repositories.UserRepository
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.BuildConfig
import com.github.mytv.bv.R
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.addDistinctWithMainContext
import com.github.mytv.bv.util.fInfo
import com.github.mytv.bv.util.fWarn
import com.github.mytv.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        /**
         * -352（风控/限流）时的总尝试次数。feed/space 对同一个 host_mid 连着请求很容易
         * 被突发限流挡住，多缓一两次往往就放行了，所以比普通错误多试一次。
         */
        private const val DYNAMIC_PAGE_RISK_ATTEMPTS = 3

        /** 两次动态分页请求之间的间隔：-352 多半是突发限流，缓一下再试就有了。 */
        private const val DYNAMIC_PAGE_RETRY_DELAY_MS = 1000L
    }

    val dynamicVideoList = mutableStateListOf<DynamicVideo>()
    val dynamicAllList = mutableStateListOf<DynamicItem>()

    /** 「全部动态」的在途分页请求；换 UP 主筛选时取消，别让它的迟到失败弹到新筛选上。 */
    private var allLoadJob: Job? = null

    /** 「UP 主空间动态」的在途分页请求；同一个 UP 同时只拉一页，换 UP 时取消上一页。 */
    private var spaceLoadJob: Job? = null

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

    /**
     * 加载用户[hostMid]的一页空间动态。
     *
     * 同一个 UP 同时只允许一页在途（[spaceLoadJob]），在途时再来一页直接忽略——
     * autoFill、滚到底、下拉刷新都会调它。返回的 Job 给下拉刷新用来等这一页结束，
     * 被跳过（已有同 UP 的在途请求）时返回 null。
     */
    fun loadSpacePage(hostMid: Long): Job? {
        if (spaceLoadJob?.isActive == true) return null
        spaceLoadJob = viewModelScope.launch { loadMoreSpace(hostMid) }
        return spaceLoadJob
    }

    /**
     * 加载一页关注动态流（「全部动态」）。同 [loadSpacePage]，同一时刻只拉一页。
     */
    fun loadAllPage(): Job? {
        if (allLoadJob?.isActive == true) return null
        allLoadJob = viewModelScope.launch { loadAllData() }
        return allLoadJob
    }

    /**
     * 取消在途的动态分页请求（换 UP 主筛选时调用）。
     *
     * 不取消的话，切走之前发出去的那一页失败时，错误会弹在用户正在看的新筛选上——
     * 表现就是「UP 主的动态已经刷出来了，却还提示加载动态失败」。取消走的是
     * CancellationException，各个加载函数的失败分支都认得它，不会弹提示。
     */
    fun cancelPendingLoads() {
        allLoadJob?.cancel()
        allLoadJob = null
        spaceLoadJob?.cancel()
        spaceLoadJob = null
    }

    /**
     * 加载用户[hostMid]的动态。
     *
     * 换 UP 前调用方已经 [clearSpaceData]（会取消上一页的在途请求并重置分页状态），
     * 所以这里看到的 hostMid 一定还是用户正在看的 UP。
     */
    private suspend fun loadMoreSpace(hostMid: Long) {
        if (!spaceHasMore || !bvUserRepository.isLogin) return
        spaceHostMid = hostMid
        loadingSpace = true
        logger.fInfo { "Load more space dynamics [mid=$hostMid, offset=$spaceHistoryOffset, page=${currentSpacePage + 1}]" }
        val nextPage = currentSpacePage + 1
        try {
            runCatching {
                val dynamicData = withDynamicPageRetry(hostMid) {
                    userRepository.getSpaceDynamics(
                        hostMid = hostMid,
                        page = nextPage,
                        offset = spaceHistoryOffset ?: "",
                        preferApiType = Prefs.apiType
                    )
                }
                // 请求期间换了 UP：这份数据是上一个 UP 的，别混进新 UP 的列表里
                if (spaceHostMid != hostMid) return
                currentSpacePage = nextPage
                dynamicSpaceList.addDistinctWithMainContext(dynamicData.dynamics) { it.id }
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
                // 列表里已经有这个 UP 的动态：这一页失败不影响眼前的内容，只把原因记下来
                // （列表空时显示、并拦住 autoFill 的自动重试），别再弹「加载动态失败」
                if (dynamicSpaceList.isNotEmpty()) return@onFailure
                withContext(Dispatchers.Main) {
                    message.toast(BVApp.context)
                }
            }
        } finally {
            // 换 UP 时这一页会被取消（spaceLoadJob 已经换成新的了）：别把新一页的
            // loading 标志一起抹掉，否则转圈会提前消失、autoFill 还会空转一轮
            if (spaceLoadJob == null || spaceLoadJob === coroutineContext[Job]) loadingSpace = false
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
        var retryDelayMs = DYNAMIC_PAGE_RETRY_DELAY_MS
        var attempt = 0
        while (true) {
            attempt++
            try {
                return request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                logger.fInfo {
                    "Dynamic page request failed [attempt=$attempt]: ${e.message}"
                }
                // 登录态问题重试没有意义，直接失败
                if (e is AuthFailureException) break
                // 重试前换了 UP：这一页属于上一个 UP
                if (hostMid != null && spaceHostMid != hostMid) break
                // 限流多试一次、也多缓一会儿：-352 的封锁窗口比普通错误的恢复时间长
                val maxAttempts =
                    if (e is RiskControlException) DYNAMIC_PAGE_RISK_ATTEMPTS else DYNAMIC_PAGE_MAX_ATTEMPTS
                retryDelayMs =
                    if (e is RiskControlException) DYNAMIC_PAGE_RETRY_DELAY_MS * 2 else DYNAMIC_PAGE_RETRY_DELAY_MS
                if (attempt >= maxAttempts) break
            }
            delay(retryDelayMs)
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
                val dynamicVideoData = withDynamicPageRetry {
                    userRepository.getDynamicVideos(
                        page = nextPage,
                        offset = videoHistoryOffset ?: "",
                        updateBaseline = videoUpdateBaseline ?: "",
                        preferApiType = Prefs.apiType
                    )
                }
                currentVideoPage = nextPage
                dynamicVideoList.addDistinctWithMainContext(dynamicVideoData.videos) { it.aid }
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
                        // 首页那一行已经有视频：这一页失败不影响眼前的内容，别再弹提示
                        if (dynamicVideoList.isNotEmpty()) return@onFailure
                        withContext(Dispatchers.Main) {
                            "动态被风控拦截，请稍后重试".toast(BVApp.context)
                        }
                    }

                    else -> {
                        // 首页那一行已经有视频：这一页失败不影响眼前的内容，别再弹提示
                        if (dynamicVideoList.isNotEmpty()) return@onFailure
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
                dynamicAllList.addDistinctWithMainContext(dynamicData.dynamics) { it.id }
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
                // 列表里已经有动态：这一页失败不影响眼前的内容，只记下原因（列表空时显示），
                // 别再弹「加载动态失败」——这正是「动态刷出来了却还提示失败」的来源
                if (dynamicAllList.isNotEmpty()) return@onFailure
                withContext(Dispatchers.Main) {
                    message.toast(BVApp.context)
                }
            }
        } finally {
            // 同 loadMoreSpace：被取消的那一页别把新一页的 loading 标志抹掉
            if (allLoadJob == null || allLoadJob === coroutineContext[Job]) loadingAll = false
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
        allLoadJob?.cancel()
        allLoadJob = null
        dynamicAllList.clear()
        currentAllPage = 0
        loadingAll = false
        allHasMore = true
        allError = null
        allHistoryOffset = null
    }

    fun clearSpaceData() {
        // 在途的那一页已经是旧状态了：取消掉，别让它的响应/失败写进新一页
        spaceLoadJob?.cancel()
        spaceLoadJob = null
        dynamicSpaceList.clear()
        currentSpacePage = 0
        loadingSpace = false
        spaceHasMore = true
        spaceError = null
        spaceHistoryOffset = null
        spaceHostMid = null
    }
}