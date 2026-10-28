package com.github.mytv.bv.player

import android.view.Surface
import com.github.mytv.bv.player.entity.AudioLoudness

abstract class AbstractVideoPlayer {
    /** 播放器事件回调 */
    protected var mPlayerEventListener: VideoPlayerListener? = null

    /**
     * 初始化播放器实例
     * 视频播放器第一步：创建视频播放器
     */
    abstract fun initPlayer()

    /** 设置请求头 */
    abstract fun setHeader(headers: Map<String, String>)

    /** 设置播放地址（单地址，行为与之前完全一致） */
    abstract fun playUrl(videoUrl: String? = null, audioUrl: String? = null)

    /**
     * 设置候选播放地址列表：用第一个地址起播，播放失败或频繁缓冲时自动切到下一个候选。
     *
     * 默认实现只播放第一个地址（等价于旧签名），需要自动切换的播放器实现应覆盖此方法。
     * [videoUrls] 为空时不会起播。
     */
    open fun playUrl(videoUrls: List<String>, audioUrls: List<String> = emptyList()) {
        playUrl(videoUrls.firstOrNull(), audioUrls.firstOrNull())
    }

    /** 运行时开关「播放失败/频繁缓冲自动切候选」；默认实现忽略（仅单地址能力） */
    open fun setCdnFailoverEnabled(enabled: Boolean) {}

    /** 准备开始播放 */
    abstract fun prepare()

    /** 播放 */
    abstract fun start()

    /** 暂停 */
    abstract fun pause()

    /** 停止 */
    abstract fun stop()

    /** 重置播放器 */
    abstract fun reset()

    /** 是否正在播放 */
    abstract val isPlaying: Boolean

    /** 跳转播放位置 */
    abstract fun seekTo(time: Long)

    /** 释放播放器 */
    abstract fun release()

    /** 当前播放位置 */
    abstract val currentPosition: Long

    /** 视频总时长 */
    abstract val duration: Long

    /** 缓冲百分比 */
    abstract val bufferedPercentage: Int

    /** 设置其他播放配置 */
    abstract fun setOptions()

    /**
     * 把一块 Surface 设为视频输出面；null 表示摘掉当前输出面。
     *
     * 「TextureView / GLSurfaceView」渲染方式用（见 [BvVideoPlayer] 的 renderMode）：
     * Surface 由 App 自己创建，调用方负责在它失效前先置空。不同内核的等价调用是
     * ExoPlayer `setVideoSurface` / IJK `setSurface` / VLC `vlcVout.setVideoSurface`。
     *
     * 切换渲染方式、Surface 重建时都遵循「**先置空、再设新的**」：同尺寸的新 Surface 会让
     * 解码器销毁并重建输出，同时泄漏图形缓冲，所以不能每次重组都重设一次。
     * 默认实现忽略（没有自建 Surface 能力的内核）。
     */
    open fun setVideoSurface(surface: Surface?) {}

    /** 播放速度 */
    abstract var speed: Float

    /**
     * 推入本集的响度元数据，null 表示没有元数据（旁路）。
     *
     * 换集/换清晰度后都必须重新推一次：处理器按当前元数据算静态增益，
     * 不推会沿用上一集的值。
     */
    open fun setAudioLoudness(loudness: AudioLoudness?) {}

    /** 当前媒体是否为直播流（HLS 直播窗口等场景需要区分点播/直播行为） */
    open val isLive: Boolean
        get() = false

    /** 当前缓冲的网速 */
    abstract val tcpSpeed: Long

    /** 调试信息 */
    abstract val debugInfo: String

    /** 视频宽度 */
    abstract val videoWidth: Int

    /** 视频高度 */
    abstract val videoHeight: Int

    /**
     * 绑定VideoView，监听播放异常，完成，开始准备，视频size变化，视频信息等操作
     */
    fun setPlayerEventListener(playerEventListener: VideoPlayerListener?) {
        mPlayerEventListener = playerEventListener
    }
}