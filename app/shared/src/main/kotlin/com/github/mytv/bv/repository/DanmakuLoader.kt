package com.github.mytv.bv.repository

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.mytv.bv.player.danmaku.model.Danmaku
import com.github.mytv.bv.util.Prefs
import dev.aaa1115910.biliapi.repositories.VideoPlayRepository

/**
 * 按 cid 拉弹幕并转换成弹幕引擎的模型。播放器和刷刷共用一份（做法同 [PlayUrlResolver]）。
 *
 * App 模式走 gRPC 分段弹幕（6 分钟一段），Web 模式走 XML；回退由仓库内部处理。
 */
suspend fun VideoPlayRepository.loadEngineDanmakus(aid: Long, cid: Long): List<Danmaku> =
    getDanmakuData(aid = aid, cid = cid, preferApiType = Prefs.apiType).map {
        Danmaku(
            dmid = it.dmid,
            positionMs = (it.time * 1000).toInt(),
            text = it.text,
            // 4 = 底部固定，5 = 顶部固定
            type = when (it.type) {
                4 -> Danmaku.MODE_BOTTOM
                5 -> Danmaku.MODE_TOP
                else -> Danmaku.MODE_SCROLL
            },
            textSize = it.size,
            color = Color(it.color).toArgb(),
            level = it.level,
        )
    }
