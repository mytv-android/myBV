package com.github.mytv.bv.ui.widget

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 小组件里使用的一条视频。progress / duration 单位为秒，progress 为 -1 表示已看完。 */
@Serializable
internal data class WidgetVideo(
    val aid: Long = 0,
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val upName: String = "",
    val duration: Int = 0,
    val progress: Int = 0,
    val seasonId: Int = 0,
    val epId: Int = 0,
    val pgc: Boolean = false,
) {
    val remainingMinutes: Int
        get() = ((duration - progress).coerceAtLeast(0) + 59) / 60

    val progressFraction: Float
        get() = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

@Serializable
internal data class ContinueSnapshot(
    val loggedIn: Boolean = false,
    val video: WidgetVideo? = null,
    val fetchedAt: Long = 0,
)

@Serializable
internal data class LaterSnapshot(
    val loggedIn: Boolean = false,
    val items: List<WidgetVideo> = emptyList(),
    val total: Int = 0,
    val fetchedAt: Long = 0,
)

@Serializable
internal data class TimelineEntry(
    val title: String = "",
    val cover: String = "",
    val seasonId: Int = 0,
    val episode: String = "",
    val time: String = "",
    val published: Boolean = false,
    val followed: Boolean = false,
)

@Serializable
internal data class TimelineSnapshot(
    val today: Int = 1,
    val week: List<Boolean> = List(7) { false },
    val items: List<TimelineEntry> = emptyList(),
    val fetchedAt: Long = 0,
)

@Serializable
internal data class DynamicSnapshot(
    val loggedIn: Boolean = false,
    val newCount: Int = 0,
    val avatars: List<String> = emptyList(),
    val latest: String = "",
    val fetchedAt: Long = 0,
)

@Serializable
internal data class ShortsSnapshot(
    val item: WidgetVideo? = null,
    val likeText: String = "",
    val seen: List<Long> = emptyList(),
    val fetchedAt: Long = 0,
)

@Serializable
internal data class SearchSnapshot(
    val hotword: String = "",
    val fetchedAt: Long = 0,
)

/** 小组件快照的本地缓存：数据网络刷新成功后写入，桌面渲染只读这里。 */
internal object WidgetStore {
    private const val PREFS_NAME = "bv_widget_state"
    private const val KEY_CONTINUE = "continue"
    private const val KEY_LATER = "later"
    private const val KEY_TIMELINE = "timeline"
    private const val KEY_DYNAMIC = "dynamic"
    private const val KEY_SHORTS = "shorts"
    private const val KEY_SEARCH = "search"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private inline fun <reified T> read(context: Context, key: String): T? =
        prefs(context).getString(key, null)?.let {
            runCatching { json.decodeFromString<T>(it) }.getOrNull()
        }

    private inline fun <reified T> write(context: Context, key: String, value: T) {
        prefs(context).edit().putString(key, json.encodeToString(value)).apply()
    }

    fun readContinue(context: Context) = read<ContinueSnapshot>(context, KEY_CONTINUE)

    fun writeContinue(context: Context, value: ContinueSnapshot) =
        write(context, KEY_CONTINUE, value)

    fun readLater(context: Context) = read<LaterSnapshot>(context, KEY_LATER)

    fun writeLater(context: Context, value: LaterSnapshot) = write(context, KEY_LATER, value)

    fun readTimeline(context: Context) = read<TimelineSnapshot>(context, KEY_TIMELINE)

    fun writeTimeline(context: Context, value: TimelineSnapshot) =
        write(context, KEY_TIMELINE, value)

    fun readDynamic(context: Context) = read<DynamicSnapshot>(context, KEY_DYNAMIC)

    fun writeDynamic(context: Context, value: DynamicSnapshot) = write(context, KEY_DYNAMIC, value)

    fun readShorts(context: Context) = read<ShortsSnapshot>(context, KEY_SHORTS)

    fun writeShorts(context: Context, value: ShortsSnapshot) = write(context, KEY_SHORTS, value)

    fun readSearch(context: Context) = read<SearchSnapshot>(context, KEY_SEARCH)

    fun writeSearch(context: Context, value: SearchSnapshot) = write(context, KEY_SEARCH, value)

    /** 从组件进入动态页后清掉未读计数。 */
    fun markDynamicSeen(context: Context) {
        val snapshot = readDynamic(context) ?: return
        if (snapshot.newCount == 0) return
        writeDynamic(context, snapshot.copy(newCount = 0))
    }

    /** 从组件进入刷刷后调：记下已看过的视频，并把快照标记为需要换下一条。 */
    fun markShortsSeen(context: Context, aid: Long) {
        if (aid <= 0) return
        val snapshot = readShorts(context) ?: return
        writeShorts(
            context,
            snapshot.copy(
                seen = (snapshot.seen + aid).takeLast(20),
                fetchedAt = 0,
            )
        )
    }
}

internal fun isFresh(fetchedAt: Long, ttlMillis: Long): Boolean =
    fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < ttlMillis
