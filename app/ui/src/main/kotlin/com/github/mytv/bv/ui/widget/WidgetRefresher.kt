package com.github.mytv.bv.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.theme.CoverSeedCache
import com.github.mytv.bv.util.Prefs
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.season.TimelineEp
import dev.aaa1115910.biliapi.entity.season.TimelineFilter
import dev.aaa1115910.biliapi.entity.story.StoryPage
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicType
import dev.aaa1115910.biliapi.entity.user.HistoryItemType
import dev.aaa1115910.biliapi.entity.user.ToViewItemType
import dev.aaa1115910.biliapi.repositories.HistoryRepository
import dev.aaa1115910.biliapi.repositories.SearchRepository
import dev.aaa1115910.biliapi.repositories.SeasonRepository
import dev.aaa1115910.biliapi.repositories.StoryRepository
import dev.aaa1115910.biliapi.repositories.ToViewRepository
import dev.aaa1115910.biliapi.repositories.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val TTL_CONTINUE = 60_000L
internal const val TTL_LATER = 30 * 60_000L
internal const val TTL_TIMELINE = 30 * 60_000L
internal const val TTL_DYNAMIC = 30 * 60_000L
internal const val TTL_SHORTS = 30 * 60_000L
internal const val TTL_SEARCH = 2 * 60 * 60_000L

/**
 * 小组件的数据刷新。网络请求都在这里完成，结果写进 [WidgetStore]；
 * Glance 的 provideGlance 只读缓存，避免桌面渲染被网络拖住。
 */
internal object WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val continueLock = Mutex()
    private val laterLock = Mutex()
    private val timelineLock = Mutex()
    private val dynamicLock = Mutex()
    private val shortsLock = Mutex()
    private val searchLock = Mutex()

    private inline fun <reified T : Any> koin(): T? =
        runCatching { BVApp.koinApplication.koin.get<T>() }.getOrNull()

    private fun widgets(): List<GlanceAppWidget> = listOf(
        ContinueWidget(),
        LaterWidget(),
        TimelineWidget(),
        DynamicWidget(),
        ShortsWidget(),
        SearchWidget(),
    )

    /** 桌面上没有放任何组件时不做周期网络请求。 */
    private suspend fun hasWidgets(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return runCatching { manager.getGlanceIds(ContinueWidget::class.java).isNotEmpty() }
            .getOrDefault(false) ||
                runCatching { manager.getGlanceIds(LaterWidget::class.java).isNotEmpty() }
                    .getOrDefault(false) ||
                runCatching { manager.getGlanceIds(TimelineWidget::class.java).isNotEmpty() }
                    .getOrDefault(false) ||
                runCatching { manager.getGlanceIds(DynamicWidget::class.java).isNotEmpty() }
                    .getOrDefault(false) ||
                runCatching { manager.getGlanceIds(ShortsWidget::class.java).isNotEmpty() }
                    .getOrDefault(false) ||
                runCatching { manager.getGlanceIds(SearchWidget::class.java).isNotEmpty() }
                    .getOrDefault(false)
    }

    /** 首次打开组件、桌面请求更新时调用；数据还在 TTL 内就不会再发请求。 */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                // 拉取失败时不触发重绘，避免「渲染 → 请求 → 失败 → 再渲染」的循环
                if (refreshAll(app, forceData = false)) updateWidgets(app)
            }
        }
    }

    /** 回到桌面 / 周期任务：继续观看、稍后再看等按最新数据刷新。 */
    fun refreshNow(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                refreshAll(app, forceData = true)
                updateWidgets(app)
            }
        }
    }

    /** 播放进度、稍后再看变化后刷新继续观看 + 稍后再看（调用方按一分钟节流）。 */
    fun requestUserDataChanged(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (!hasWidgets(app)) return@launch
                coroutineScope {
                    launch { runCatching { refreshContinue(app, force = true) } }
                    launch { runCatching { refreshLater(app, force = true) } }
                }
                updateWidgets(app)
            }
        }
    }

    /** 从组件进入刷刷后：换下一条没看过的。 */
    fun requestShorts(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (!hasWidgets(app)) return@launch
                refreshShorts(app, force = true)
                updateWidgets(app)
            }
        }
    }

    /** 主题偏好变了，只需要让 Glance 重新取色。 */
    fun updateWidgetsSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { updateWidgets(app) } }
    }

    /** @return 是否写入了新的快照；没有任何数据变化时调用方不必重绘桌面。 */
    suspend fun refreshAll(context: Context, forceData: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasWidgets(context)) return@withContext false
            // 首次放到桌面时 App 可能还没打开过，这里补上周期刷新
            WidgetScheduler.ensureScheduled(context)
            coroutineScope {
                listOf(
                    async { runCatching { refreshContinue(context, forceData) }.getOrDefault(false) },
                    async { runCatching { refreshLater(context, forceData) }.getOrDefault(false) },
                    async { runCatching { refreshTimeline(context, forceData) }.getOrDefault(false) },
                    async { runCatching { refreshDynamic(context, forceData) }.getOrDefault(false) },
                    async { runCatching { refreshShorts(context, force = false) }.getOrDefault(false) },
                    async { runCatching { refreshSearch(context, force = false) }.getOrDefault(false) },
                ).awaitAll().any { it }
            }
        }

    suspend fun updateWidgets(context: Context) {
        widgets().forEach { widget ->
            runCatching { widget.updateAll(context) }
        }
    }

    private suspend fun refreshContinue(context: Context, force: Boolean): Boolean =
        continueLock.withLock {
            val cached = WidgetStore.readContinue(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_CONTINUE)) {
                return@withLock false
            }
            val now = System.currentTimeMillis()
            if (!Prefs.isLogin) {
                WidgetStore.writeContinue(context, ContinueSnapshot(loggedIn = false, fetchedAt = now))
                return@withLock true
            }
            val repository = koin<HistoryRepository>() ?: return@withLock false
            val data = runCatching {
                repository.getHistories(cursor = 0, preferApiType = Prefs.apiType)
            }.getOrNull() ?: return@withLock false
            val item = data.data.firstOrNull { it.progress in 1 until it.duration }
            val video = item?.let {
                WidgetVideo(
                    aid = it.oid,
                    cid = it.cid,
                    title = it.title,
                    cover = it.cover,
                    upName = it.author,
                    duration = it.duration,
                    progress = it.progress,
                    seasonId = it.seasonId ?: 0,
                    epId = it.epid ?: 0,
                    pgc = it.type == HistoryItemType.Pgc,
                )
            }
            video?.cover?.takeIf { it.isNotBlank() }?.let {
                runCatching { CoverSeedCache.get(context, widgetImageUrl(it)) }
            }
            WidgetStore.writeContinue(
                context,
                ContinueSnapshot(loggedIn = true, video = video, fetchedAt = now),
            )
            true
        }

    private suspend fun refreshLater(context: Context, force: Boolean): Boolean =
        laterLock.withLock {
            val cached = WidgetStore.readLater(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_LATER)) {
                return@withLock false
            }
            val now = System.currentTimeMillis()
            if (!Prefs.isLogin) {
                WidgetStore.writeLater(context, LaterSnapshot(loggedIn = false, fetchedAt = now))
                return@withLock true
            }
            val repository = koin<ToViewRepository>() ?: return@withLock false
            val data = runCatching {
                repository.getToView(cursor = 0, preferApiType = Prefs.apiType)
            }.getOrNull() ?: return@withLock false
            val items = data.data.map {
                WidgetVideo(
                    aid = it.oid,
                    cid = it.cid,
                    title = it.title,
                    cover = it.cover,
                    upName = it.author,
                    duration = it.duration,
                    progress = it.progress.coerceAtLeast(0),
                    seasonId = it.seasonId ?: 0,
                    epId = it.epid ?: 0,
                    pgc = it.type == ToViewItemType.Pgc,
                )
            }
            WidgetStore.writeLater(
                context,
                LaterSnapshot(
                    loggedIn = true,
                    items = items,
                    total = items.size,
                    fetchedAt = now,
                ),
            )
            true
        }

    private suspend fun refreshTimeline(context: Context, force: Boolean): Boolean =
        timelineLock.withLock {
            val cached = WidgetStore.readTimeline(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_TIMELINE)) {
                return@withLock false
            }
            val repository = koin<SeasonRepository>() ?: return@withLock false
            val timelines = runCatching {
                repository.getTimeline(filter = TimelineFilter.Anime, preferApiType = Prefs.apiType)
            }.getOrNull() ?: return@withLock false
            val today = timelines.firstOrNull { it.isToday } ?: return@withLock false
            val now = System.currentTimeMillis()
            val week = (1..7).map { day ->
                timelines.firstOrNull { it.dayOfWeek == day }?.episodes?.any { it.follow } == true
            }
            val items = today.episodes
                .sortedWith(compareByDescending<TimelineEp> { it.follow }.thenBy { it.publishTime })
                .map {
                    TimelineEntry(
                        title = it.title,
                        cover = it.cover,
                        seasonId = it.seasonId,
                        episode = it.publishIndex,
                        time = it.publishTime,
                        published = it.published || it.publishDate.time <= now,
                        followed = it.follow,
                    )
                }
            WidgetStore.writeTimeline(
                context,
                TimelineSnapshot(
                    today = today.dayOfWeek,
                    week = week,
                    items = items,
                    fetchedAt = now,
                ),
            )
            WidgetScheduler.scheduleTimelineTick(
                context,
                today.episodes.map { it.publishDate.time }.filter { it > now }.minOrNull(),
            )
            true
        }

    /** 番剧到点：只刷新时间表并重绘。 */
    suspend fun refreshTimelineAndUpdate(context: Context) {
        withContext(Dispatchers.IO) {
            if (hasWidgets(context)) runCatching { refreshTimeline(context, force = true) }
        }
        updateWidgets(context)
    }

    private suspend fun refreshDynamic(context: Context, force: Boolean): Boolean =
        dynamicLock.withLock {
            val cached = WidgetStore.readDynamic(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_DYNAMIC)) {
                return@withLock false
            }
            val now = System.currentTimeMillis()
            if (!Prefs.isLogin) {
                WidgetStore.writeDynamic(context, DynamicSnapshot(loggedIn = false, fetchedAt = now))
                return@withLock true
            }
            val repository = koin<UserRepository>() ?: return@withLock false
            val data = runCatching {
                repository.getDynamics(
                    page = 1,
                    offset = "",
                    updateBaseline = "",
                    preferApiType = Prefs.apiType,
                )
            }.getOrNull() ?: return@withLock false
            val items = data.dynamics
            val count = data.newCount.takeIf { it > 0 }
                ?: if (Prefs.apiType == ApiType.Web) 0 else items.size
            val avatars = items.mapNotNull { it.author.avatar.takeIf(String::isNotBlank) }
                .distinct()
                .take(3)
            val latest = items.firstOrNull()?.let { item ->
                buildString {
                    append(item.author.author)
                    append(' ')
                    append(dynamicAction(context, item))
                    val time = item.author.pubTime
                    if (time.isNotBlank()) {
                        append(" · ")
                        append(time)
                    }
                }
            }.orEmpty()
            WidgetStore.writeDynamic(
                context,
                DynamicSnapshot(
                    loggedIn = true,
                    newCount = count,
                    avatars = avatars,
                    latest = latest,
                    fetchedAt = now,
                ),
            )
            true
        }

    private fun dynamicAction(context: Context, item: DynamicItem): String = context.getString(
        when (item.type) {
            DynamicType.Av, DynamicType.UgcSeason -> R.string.widget_dynamic_action_video
            DynamicType.Pgc -> R.string.widget_dynamic_action_pgc
            DynamicType.Article -> R.string.widget_dynamic_action_article
            DynamicType.LiveRcmd -> R.string.widget_dynamic_action_live
            DynamicType.Forward -> R.string.widget_dynamic_action_forward
            else -> R.string.widget_dynamic_action_default
        }
    )

    private suspend fun refreshShorts(context: Context, force: Boolean): Boolean =
        shortsLock.withLock {
            val cached = WidgetStore.readShorts(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_SHORTS)) {
                return@withLock false
            }
            val repository = koin<StoryRepository>() ?: return@withLock false
            val data = runCatching {
                repository.getStories(page = StoryPage(), fallbackApiType = Prefs.apiType)
            }.getOrNull() ?: return@withLock false
            val seen = cached?.seen.orEmpty()
            val item = data.items.firstOrNull { it.dimension?.isVertical == true && it.aid !in seen }
                ?: data.items.firstOrNull { it.aid !in seen }
                ?: data.items.firstOrNull()
                ?: return@withLock false
            item.cover.takeIf { it.isNotBlank() }?.let {
                runCatching { CoverSeedCache.get(context, widgetImageUrl(it)) }
            }
            WidgetStore.writeShorts(
                context,
                ShortsSnapshot(
                    item = WidgetVideo(
                        aid = item.aid,
                        cid = item.cid,
                        title = item.title,
                        cover = item.cover,
                        upName = item.author.name,
                        duration = item.duration,
                    ),
                    likeText = item.like?.let(::formatPlayCount).orEmpty(),
                    seen = seen,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            true
        }

    private suspend fun refreshSearch(context: Context, force: Boolean): Boolean =
        searchLock.withLock {
            val cached = WidgetStore.readSearch(context)
            if (!force && cached != null && isFresh(cached.fetchedAt, TTL_SEARCH)) {
                return@withLock false
            }
            val repository = koin<SearchRepository>() ?: return@withLock false
            val hotwords = runCatching {
                repository.getSearchHotwords(limit = 10, preferApiType = Prefs.apiType)
            }.getOrNull() ?: return@withLock false
            val word = hotwords.firstOrNull()?.showName?.takeIf { it.isNotBlank() }
                ?: hotwords.firstOrNull()?.keyword
                ?: return@withLock false
            WidgetStore.writeSearch(
                context,
                SearchSnapshot(hotword = word, fetchedAt = System.currentTimeMillis()),
            )
            true
        }
}

internal fun formatPlayCount(value: Long): String = when {
    value >= 100_000_000 -> trimCount(value / 100_000_000.0) + "亿"
    value >= 10_000 -> trimCount(value / 10_000.0) + "万"
    else -> value.toString()
}

private fun trimCount(value: Double): String =
    if (value >= 10) {
        value.toInt().toString()
    } else {
        String.format(Locale.CHINA, "%.1f", value).trimEnd('0').trimEnd('.')
    }

/** 视频时长 / 进度，秒 → m:ss。 */
internal fun formatClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${String.format(Locale.US, "%02d", safe % 60)}"
}
