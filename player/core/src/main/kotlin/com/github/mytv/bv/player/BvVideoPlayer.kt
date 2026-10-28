package com.github.mytv.bv.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.mytv.bv.player.entity.VideoRenderMode
import com.github.mytv.bv.player.impl.exo.ExoMediaPlayer
import kotlinx.coroutines.delay

/**
 * 播放器画面：按 [renderMode] 选渲染方式，默认 [VideoRenderMode.SurfaceView]
 * （解码器直出到 PlayerView 自带的 SurfaceView）。
 *
 * 另外两种方式把解码帧交给 App 的 SurfaceTexture，YUV→RGB 的范围换算回到 App 侧，
 * 用来绕开部分电视盒子在 SurfaceView 下把有限范围 YUV 按全范围合成导致的发白问题。
 * 代价见 [VideoRenderMode] 与 [BvGLSurfaceView]。
 *
 * @param renderMode 渲染方式。切换时会重建画面 View：旧方式的输出面先摘掉，
 *   新方式拿到 Surface 后再设，不会每次重组都重设一次。
 */
@OptIn(UnstableApi::class)
@Composable
fun BvVideoPlayer(
    modifier: Modifier = Modifier,
    videoPlayer: AbstractVideoPlayer,
    playerListener: VideoPlayerListener,
    renderMode: VideoRenderMode = VideoRenderMode.SurfaceView,
) {
    DisposableEffect(videoPlayer) {
        onDispose { videoPlayer.release() }
    }
    // 切换渲染方式 / 换播放内核 / 离开播放页：先把播放器当前持有的输出面摘掉，
    // 新的画面 View 拿到自己的 Surface 之后再设。不能「每次重组都重设一次」——
    // 同尺寸的新 Surface 会让解码器销毁并重建输出，同时泄漏图形缓冲。
    // SurfaceView 模式下 PlayerView 自己管这块 Surface，这里兜底清一次没有副作用。
    DisposableEffect(renderMode, videoPlayer) {
        onDispose { videoPlayer.setVideoSurface(null) }
    }
    SideEffect {
        videoPlayer.setPlayerEventListener(playerListener)
    }

    when (videoPlayer) {
        is ExoMediaPlayer -> when (renderMode) {
            // 默认：SurfaceView 直出，Surface 生命周期完全由 media3 的 PlayerView 管
            VideoRenderMode.SurfaceView -> AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = videoPlayer.mPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        useController = false
                    }
                },
                update = { view ->
                    if (view.player !== videoPlayer.mPlayer) view.player = videoPlayer.mPlayer
                },
            )

            VideoRenderMode.TextureView -> TextureViewVideoSurface(modifier.fillMaxSize(), videoPlayer)
            VideoRenderMode.GLSurfaceView -> GlVideoSurface(modifier.fillMaxSize(), videoPlayer)
        }
    }
}

/**
 * 「TextureView」渲染方式：把播放器的输出面交给 TextureView 的 SurfaceTexture。
 *
 * 与 [GlVideoSurface] 同理，能绕开 SurfaceView 下发白的合成问题；这里不需要自己建 GL 管线
 * （采样由 TextureView 内部完成），SurfaceTexture 的生命周期也由 TextureView 自己管，
 * 只需在可用 / 即将销毁时把 Surface 挂上 / 摘掉。
 */
@Composable
private fun TextureViewVideoSurface(
    modifier: Modifier,
    videoPlayer: AbstractVideoPlayer,
) {
    val context = LocalContext.current
    val view = remember(context) { TextureView(context) }

    DisposableEffect(videoPlayer, view) {
        var surface: Surface? = null
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                // 先摘旧的再设新的：同尺寸的新 Surface 会让解码器销毁并重建输出，同时泄漏图形缓冲
                videoPlayer.setVideoSurface(null)
                surface = Surface(st).also { videoPlayer.setVideoSurface(it) }
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // SurfaceTexture 即将被销毁：先把播放器的输出面摘掉，再释放包装它的 Surface
                videoPlayer.setVideoSurface(null)
                surface?.release()
                surface = null
                // SurfaceTexture 交给 TextureView 释放
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        view.surfaceTextureListener = listener
        // 组合重建时 View 可能已经带着 SurfaceTexture 了，监听器不会补回调，手动补一次
        if (view.isAvailable) {
            view.surfaceTexture?.let { listener.onSurfaceTextureAvailable(it, view.width, view.height) }
        }
        onDispose {
            view.surfaceTextureListener = null
            videoPlayer.setVideoSurface(null)
            surface?.release()
            surface = null
        }
    }

    AndroidView(modifier = modifier, factory = { view }, update = {})
}

/**
 * 「GLSurfaceView」渲染方式：解码帧进 [BvGLSurfaceView] 提供的 Surface，
 * 再由它自己的 GLES 纯直通采样画到输出面。
 */
@Composable
private fun GlVideoSurface(
    modifier: Modifier,
    videoPlayer: AbstractVideoPlayer,
) {
    val context = LocalContext.current
    val view = remember(context) { BvGLSurfaceView(context) }

    // 已知视频尺寸时告诉 SurfaceTexture 默认 buffer 尺寸。只在这个渲染方式下跑，
    // 500ms 轮询一次的开销可以忽略，也省得给共享的播放器监听器加接口。
    LaunchedEffect(videoPlayer, view) {
        var lastWidth = 0
        var lastHeight = 0
        while (true) {
            val width = videoPlayer.videoWidth
            val height = videoPlayer.videoHeight
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                lastWidth = width
                lastHeight = height
                view.setVideoSize(width, height)
            }
            delay(500)
        }
    }

    DisposableEffect(videoPlayer, view) {
        // onSurfaceCreated 会重建 Surface（View 被移除再加回时），创建后必须把新 Surface
        // 重新交给播放器；View 只回调仍然有效的那一块，所以这里直接设即可
        view.onVideoSurfaceAvailable = { surface ->
            // 先摘旧的再设新的：同尺寸的新 Surface 会让解码器销毁并重建输出，同时泄漏图形缓冲
            videoPlayer.setVideoSurface(null)
            videoPlayer.setVideoSurface(surface)
        }
        view.onVideoSurfaceInvalidated = { videoPlayer.setVideoSurface(null) }
        onDispose {
            view.onVideoSurfaceAvailable = null
            view.onVideoSurfaceInvalidated = null
            // 先把播放器的输出面摘掉，View 自己在 onDetachedFromWindow 里释放 Surface
            videoPlayer.setVideoSurface(null)
        }
    }

    AndroidView(modifier = modifier, factory = { view }, update = {})
}
