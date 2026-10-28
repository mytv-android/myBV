package com.github.mytv.bv.player

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 「GLSurfaceView」渲染方式的画面 View：解码器输出的 Surface 由这块 View 提供，
 * 渲染器只做**纯直通**采样，不引入任何画质处理。
 *
 * ## 为什么需要它
 * 部分电视盒子的视频层 / 合成器把「有限范围」YUV 按全范围合成：用 SurfaceView 直出
 * （解码器 output surface 就是 SurfaceView 的 Surface）时，同一路 SDR、BT.709、
 * 有限范围（yuv420p tv/bt709）的直播流画面整体发白、黑位抬高；换播放内核
 * （Media3/ExoPlayer、IJK、VLC）都一样，说明与内核无关。
 *
 * 把解码帧先交给这块 View 的 SurfaceTexture，再用 App 自己的 GLES 采样画到
 * GLSurfaceView 的输出面，YUV→RGB 的范围换算就回到 App 侧完成，颜色与 TextureView
 * 完全一致（已实测验证）。
 *
 * 这条路径改不动解码器、改不动 MediaCodec 的 color keys、也改不动 HDMI 设置，
 * 唯一能做的是绕开盒子的合成器。
 *
 * ## 代价（所以它是可选项，而不是新默认）
 * - 每帧多一次 GPU 拷贝（解码输出 → SurfaceTexture → GLSurfaceView 输出面），
 *   4K 或低端盒子上帧率可能下降。
 * - **不做 HDR 色调映射**：HLG / HDR10 / PQ 内容可能出现颜色错误，
 *   HDR 场景应留在 SurfaceView。
 * - 盒子本身没有发白问题时，保持默认 SurfaceView 更好（性能更好）。
 *
 * ## Surface 生命周期
 * [onSurfaceCreated] 创建 SurfaceTexture + Surface 并通过 [onVideoSurfaceAvailable] 交给播放器；
 * [onDetachedFromWindow] 先通过 [onVideoSurfaceInvalidated] 让播放器把输出面置空，
 * 再释放 Surface / SurfaceTexture。View 被移除再加回时 [onSurfaceCreated] 会重新创建，
 * 新 Surface 必须重新交给播放器。
 *
 * **不要「每次重组都 setVideoSurface 一次」**：同尺寸的新 Surface 会让解码器销毁并重建
 * 输出，同时泄漏图形缓冲。
 *
 * @param onVideoSurfaceAvailable Surface 可用（主线程）。实现方应先置空播放器当前输出面，
 *   再设新的；只有仍然有效的 Surface 才会回调（View 内部会过滤掉已失效的）。
 * @param onVideoSurfaceInvalidated Surface 失效（主线程，[onDetachedFromWindow] 里同步调用）。
 *   实现方必须把播放器的输出面置空。
 */
class BvGLSurfaceView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer,
    SurfaceTexture.OnFrameAvailableListener {

    private companion object {
        private val logger = KotlinLogging.logger("BvGLSurfaceView")

        /** 全屏 quad 的顶点坐标（GL_TRIANGLE_STRIP），NDC。 */
        private val QUAD_POSITIONS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        /**
         * 与顶点一一对应的纹理坐标。
         * `samplerExternalOES` 的 (0,0) 在画面**左上**，所以 NDC 的上边（y = +1）取 t = 0、
         * 下边取 t = 1；朝向本身由 [SurfaceTexture.getTransformMatrix] 处理。
         */
        private val QUAD_TEX_COORDS = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTextureCoord.xy;
            }
        """

        /** 必须声明扩展，否则 samplerExternalOES 不可用。 */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy);
            }
        """
    }

    /** Surface 可用回调（主线程），见类注释。 */
    var onVideoSurfaceAvailable: ((Surface) -> Unit)? = null

    /** Surface 失效回调（主线程），见类注释。 */
    var onVideoSurfaceInvalidated: (() -> Unit)? = null

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val texMatrix = FloatArray(16)

    private val positionBuffer: FloatBuffer =
        QUAD_POSITIONS.toFloatBuffer()
    private val texCoordBuffer: FloatBuffer =
        QUAD_TEX_COORDS.toFloatBuffer()

    /** 只允许 GL 线程访问：onSurfaceCreated 创建、onSurfaceDestroyed 删除。 */
    private var textureId = 0
    private var program = 0
    private var aPositionLoc = 0
    private var aTextureCoordLoc = 0
    private var uTextureLoc = 0
    private var uTexMatrixLoc = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null

    /** 已交给播放器的 Surface；null 表示还没创建或已释放。 */
    val currentVideoSurface: Surface?
        get() = synchronized(lock) { decoderSurface }

    /** 已知视频尺寸，0 表示未知；创建 SurfaceTexture 与尺寸变化时下发。 */
    private var videoWidth = 0
    private var videoHeight = 0

    /** 有待处理的新帧（[onFrameAvailable] 置位，[onDrawFrame] 取走）。 */
    private var frameAvailable = false

    init {
        // EGL 配置 8,8,8,8：与 media3 自己的 GL 输出面一致；窗口 format 保持不透明
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        // 首帧出来前那块区域是黑的，不要透出底层
        setBackgroundColor(Color.BLACK)
        setRenderer(this)
        // 只在有新帧时画：帧到达才 updateTexImage + 重绘，空转不烧 GPU
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ------------------------------------------------------------ Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 重进窗口时会再次回调：先清掉上一轮（如果 onSurfaceDestroyed 没被调用）的 GL 对象
        releaseGlResources()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        runCatching {
            textureId = createExternalTexture()
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

            val texture = SurfaceTexture(textureId)
            synchronized(lock) {
                // 上一轮的 Surface 理论上已经被 onDetachedFromWindow 释放干净；真出现了
                // 只可能是窗口 surface 在 View 仍附着时被重建，此时不能在这里释放旧 Surface
                // （解码器还在往里写帧），交给 onDetachedFromWindow 收尾。
                if (surfaceTexture != null) {
                    logger.warn { "Previous decoder surface was not released" }
                }
                applyVideoSizeLocked(texture)
                // 先置监听再赋值，避免漏掉第一帧
                texture.setOnFrameAvailableListener(this)
                surfaceTexture = texture
                decoderSurface = Surface(texture)
            }
            notifyVideoSurfaceAvailable(texture)
        }.onFailure {
            // OES 扩展缺失 / 着色器编译失败之类：留黑屏并报错，不要让 GL 线程把进程带崩
            logger.error(it) { "Failed to set up GL video passthrough" }
            releaseVideoSurface()
            releaseGlResources()
        }
        // 没有帧到达时也画一次，把输出面刷成黑色
        requestRender()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = synchronized(lock) {
            frameAvailable = false
            surfaceTexture
        }
        if (texture == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        // 纯直通：updateTexImage + 变换矩阵，然后全屏 quad 采样，不做任何画质处理
        runCatching {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
        }.onFailure {
            logger.warn(it) { "updateTexImage failed" }
            return
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        positionBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer
        )
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun onSurfaceDestroyed(gl: GL10?) {
        // EGL 输出面已销毁（正常流程随后就是 onDetachedFromWindow）。
        // 这里只删 GL 对象；Surface / SurfaceTexture 先留着——提前释放会让解码器继续往一块
        // 已销毁的 BufferQueue 写帧。真正的释放在 onDetachedFromWindow：先把播放器的
        // 输出面摘掉，再释放。
        releaseGlResources()
    }

    // ------------------------------------------------------------ OnFrameAvailableListener

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(lock) { frameAvailable = true }
        requestRender()
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 告诉 SurfaceTexture 已知的视频尺寸（[SurfaceTexture.setDefaultBufferSize]）。
     * 不下发时解码器先按 1x1 分配输出缓冲，再改会造成一次多余的缓冲重建。
     */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            videoWidth = width
            videoHeight = height
            applyVideoSizeLocked(surfaceTexture)
        }
    }

    override fun onDetachedFromWindow() {
        // 顺序不能反：先把播放器的输出面摘掉，再释放 Surface / SurfaceTexture。
        // 播放器持有已销毁的 Surface 会让解码器输出报错并泄漏图形缓冲。
        onVideoSurfaceInvalidated?.invoke()
        // SurfaceTexture 在 GL 线程释放，避免与 onDrawFrame 的 updateTexImage 撞车；
        // queueEvent 在 GL 线程已退出时不会执行，所以 super 之后再兜底一次
        // （super 会等 GL 线程退出，所以两次调用只有一次真正释放）。
        queueEvent {
            releaseVideoSurface()
            releaseGlResources()
        }
        super.onDetachedFromWindow()
        releaseVideoSurface()
    }

    private fun releaseVideoSurface() {
        val texture: SurfaceTexture?
        val surface: Surface?
        synchronized(lock) {
            texture = surfaceTexture
            surface = decoderSurface
            surfaceTexture = null
            decoderSurface = null
            frameAvailable = false
        }
        if (texture == null && surface == null) return
        texture?.setOnFrameAvailableListener(null)
        texture?.release()
        surface?.release()
        logger.info { "Released decoder output surface" }
    }

    /** 删除 OES 纹理与 program；幂等，只能在 GL 线程调用。 */
    private fun releaseGlResources() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    /** 只有仍然有效的 Surface 才会回调，且一定切到主线程。 */
    private fun notifyVideoSurfaceAvailable(texture: SurfaceTexture) {
        val listener = onVideoSurfaceAvailable ?: return
        val surface = synchronized(lock) {
            val current = decoderSurface
            if (current == null || surfaceTexture !== texture) null else current
        } ?: return
        mainHandler.post {
            // Surface 重建期间旧回调不能把播放器写回已释放的面
            if (currentVideoSurface === surface) listener(surface)
        }
    }

    private fun applyVideoSizeLocked(texture: SurfaceTexture?) {
        if (texture == null) return
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return
        runCatching { texture.setDefaultBufferSize(width, height) }
            .onFailure { logger.warn(it) { "setDefaultBufferSize($width, $height) failed" } }
    }

    // ------------------------------------------------------------ GL 辅助

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "glGenTextures failed" }
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return id
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val handle = GLES20.glCreateProgram()
        check(handle != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(handle, vertexShader)
        GLES20.glAttachShader(handle, fragmentShader)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(handle)
            GLES20.glDeleteProgram(handle)
            error("glLinkProgram failed: $log")
        }
        // 链完就可以删了，program 自己持有引用
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return handle
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("glCompileShader failed: $log")
        }
        return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }
}
