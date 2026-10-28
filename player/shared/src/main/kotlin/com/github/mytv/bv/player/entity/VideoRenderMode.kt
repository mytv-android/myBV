package com.github.mytv.bv.player.entity

import android.content.Context
import com.github.mytv.bv.player.shared.R

/**
 * 视频画面渲染方式。
 *
 * 为什么要有这个选项：部分电视盒子的视频层 / 合成器把「有限范围」YUV 按全范围合成，
 * 用 [SurfaceView] 直出（解码器 output surface 就是 SurfaceView 的 Surface）时，
 * SDR、BT.709、有限范围的画面整体发白、黑位抬高；换播放内核（Media3 / IJK / VLC）都一样。
 * [TextureView] 与 [GLSurfaceView] 走的是 SurfaceTexture + App 自己的 GLES 采样，
 * YUV→RGB 的范围换算由 App 侧完成，颜色正确。
 *
 * **默认保持 [SurfaceView]**：零额外 GPU 拷贝，且 HDR（HLG / HDR10 / PQ）能交给显示管线。
 * 另外两种只在盒子本身有发白问题时才有意义。
 *
 * 切换生效时机：下一次进入播放页（渲染方式由播放页的 View 读取，播放内核不变）。
 * 详细代价见 [com.github.mytv.bv.player.BvGLSurfaceView] 与 docs/video-render-mode.md。
 */
enum class VideoRenderMode(private val strRes: Int) {
    /**
     * 默认：解码器直接输出到 SurfaceView 的 Surface。
     * 零额外拷贝、支持 HDR 色调映射；但绕不开合成器的范围换算 bug。
     */
    SurfaceView(R.string.render_mode_surface_view),

    /**
     * 解码帧进 TextureView 的 SurfaceTexture，由 App 侧采样后画到 View 上。
     * 与 [GLSurfaceView] 一样能修正颜色；SurfaceTexture 生命周期由 TextureView 自己管。
     */
    TextureView(R.string.render_mode_texture_view),

    /**
     * 解码帧进 App 的 GLSurfaceView 提供的 Surface，GLES 纯直通采样后画到输出面。
     * 多一次 GPU 拷贝，**不做 HDR 色调映射**；只在 SurfaceView 下发白时使用。
     */
    GLSurfaceView(R.string.render_mode_gl_surface_view);

    fun getDisplayName(context: Context) = context.getString(strRes)
}
