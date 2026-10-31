package com.github.mytv.bv.ui.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import com.github.mytv.bv.ui.MainActivity

/** 组件的点按最终都走 MainActivity 的 bugvideo:// 深链，不新增 Activity。 */
internal object WidgetActions {
    fun videoDetail(context: Context, video: WidgetVideo): Action = action(
        context,
        if (video.pgc && (video.seasonId > 0 || video.epId > 0)) {
            seasonUri(video.seasonId, video.epId)
        } else {
            videoUri(video.aid)
        },
    )

    /** cid 缺失（如 Web 端的历史记录只有 epId）时退回详情页，避免播放器拿到 0。 */
    fun resume(context: Context, video: WidgetVideo): Action =
        if (video.cid > 0) {
            action(context, playerUri(video, resume = true))
        } else {
            videoDetail(context, video)
        }

    fun toView(context: Context): Action = action(context, simpleUri("toview"))

    fun dynamic(context: Context): Action = action(context, simpleUri("dynamic"))

    fun login(context: Context): Action = action(context, simpleUri("login"))

    fun timelineDay(context: Context, day: Int): Action = action(
        context,
        Uri.Builder()
            .scheme(SCHEME)
            .authority("timeline")
            .appendQueryParameter("day", day.toString())
            .build()
            .toString(),
    )

    fun season(context: Context, seasonId: Int): Action =
        action(context, seasonUri(seasonId, epId = 0))

    fun search(context: Context, query: String): Action = action(
        context,
        Uri.Builder()
            .scheme(SCHEME)
            .authority("search")
            .appendQueryParameter("q", query)
            .build()
            .toString(),
    )

    fun shorts(context: Context, aid: Long? = null): Action = action(
        context,
        Uri.Builder()
            .scheme(SCHEME)
            .authority("shorts")
            .apply { aid?.let { appendQueryParameter("aid", it.toString()) } }
            .build()
            .toString(),
    )

    private const val SCHEME = "bugvideo"

    private fun action(context: Context, uri: String): Action = actionStartActivity(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(uri).buildUpon()
                // 同一个组件按钮重复点按：data 保持唯一，MainActivity 才会再次处理深链
                .appendQueryParameter("_w", System.nanoTime().toString())
                .build(),
            context,
            MainActivity::class.java,
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
    )

    private fun simpleUri(host: String): String =
        Uri.Builder().scheme(SCHEME).authority(host).build().toString()

    private fun videoUri(aid: Long): String =
        Uri.Builder().scheme(SCHEME).authority("video").appendPath(aid.toString()).build().toString()

    private fun seasonUri(seasonId: Int, epId: Int): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("season")
            .appendQueryParameter("seasonId", seasonId.toString())
            .apply { if (epId > 0) appendQueryParameter("epId", epId.toString()) }
            .build()
            .toString()

    private fun playerUri(video: WidgetVideo, resume: Boolean): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("player")
            .appendQueryParameter("aid", video.aid.toString())
            .appendQueryParameter("cid", video.cid.toString())
            .appendQueryParameter("title", video.title)
            .appendQueryParameter(
                "played",
                (if (resume) video.progress.coerceAtLeast(0).toLong() * 1000L else 0L).toString(),
            )
            .appendQueryParameter("fromSeason", if (video.pgc) "1" else "0")
            .appendQueryParameter("epid", video.epId.toString())
            .appendQueryParameter("seasonId", video.seasonId.toString())
            .build()
            .toString()
}
