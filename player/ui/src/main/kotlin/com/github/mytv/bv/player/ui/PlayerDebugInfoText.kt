package com.github.mytv.bv.player.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.github.mytv.bv.player.entity.LocalVideoPlayerDebugInfoData
import com.github.mytv.bv.player.ui.design.PlayerType
import kotlinx.coroutines.delay

/** 调试信息刷新间隔；帧率按 1 秒窗口统计，500ms 刷新读起来已接近实时。 */
private const val DebugInfoRefreshIntervalMs = 500L

/**
 * 播放器调试信息。
 *
 * 内容按固定间隔从 [LocalVideoPlayerDebugInfoData] 重新读取，时间与帧率才会走动；
 * 重组只发生在这一段文字上，不会把整个播放页拖进 2Hz 重组。
 */
@Composable
internal fun PlayerDebugInfoText(modifier: Modifier = Modifier) {
    val debugInfoData by rememberUpdatedState(LocalVideoPlayerDebugInfoData.current)
    var text by remember { mutableStateOf(debugInfoData.debugInfo()) }

    LaunchedEffect(Unit) {
        while (true) {
            text = debugInfoData.debugInfo()
            delay(DebugInfoRefreshIntervalMs)
        }
    }

    Text(
        modifier = modifier,
        text = text,
        style = PlayerType.Meta,
    )
}
