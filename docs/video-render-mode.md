# 视频渲染方式（SurfaceView / TextureView / GLSurfaceView）

设置项：**设置 → 播放 → 渲染方式**。默认 `SurfaceView`，**不要**改成自动默认。

代码位置：

| 项 | 位置 |
| --- | --- |
| 选项枚举 | [player/shared/.../player/entity/VideoRenderMode.kt](../player/shared/src/main/kotlin/com/github/mytv/bv/player/entity/VideoRenderMode.kt) |
| 画面分支 | [player/core/.../player/BvVideoPlayer.kt](../player/core/src/main/kotlin/com/github/mytv/bv/player/BvVideoPlayer.kt) |
| GLSurfaceView + 直通渲染器 | [player/core/.../player/BvGLSurfaceView.kt](../player/core/src/main/kotlin/com/github/mytv/bv/player/BvGLSurfaceView.kt) |
| 内核无关的输出面接口 | [player/core/.../player/AbstractVideoPlayer.kt](../player/core/src/main/kotlin/com/github/mytv/bv/player/AbstractVideoPlayer.kt)（`setVideoSurface`） |
| 设置持久化 | [app/shared/.../util/Prefs.kt](../app/shared/src/main/kotlin/com/github/mytv/bv/util/Prefs.kt)（`videoRenderMode`） |

## 现象与结论

同一路 SDR、BT.709、有限范围（`yuv420p tv/bt709`）的直播流：

- **SurfaceView 直出**（解码器 output surface 就是 SurfaceView 的 Surface）：画面整体发白、黑位抬高。
- **TextureView**：正常。

换播放内核（Media3/ExoPlayer、IJK、VLC）都一样 → 与内核无关。

**原因**：这类盒子的视频层 / 合成器把有限范围 YUV 按全范围合成。TextureView 走的是
SurfaceTexture + App 自己的 GLES 采样，YUV→RGB 的范围换算由 App 侧完成，所以颜色正确。

**已实测验证**：把播放输出的 Surface 换成一块由 App 的 GLSurfaceView 提供的 Surface
（解码帧先进 SurfaceTexture，再用 GLES 采样画到 GLSurfaceView 的输出面），
颜色与 TextureView 完全一致。

这条路径改不动解码器、改不动 MediaCodec 的 color keys、也改不动 HDMI 设置。
**不要**去改 `MediaFormat` 的 `color-range` / `color-standard` —— 已确认无效。

## 三种方式

| 方式 | 通路 | 颜色 | 开销 | HDR |
| --- | --- | --- | --- | --- |
| `SurfaceView`（默认） | 解码器 → SurfaceView 的 Surface | 取决于盒子合成器 | 零额外拷贝 | 交给显示管线 |
| `TextureView` | 解码器 → SurfaceTexture → App 采样 | App 侧换算，正确 | 一次 GPU 拷贝 | 无色调映射 |
| `GLSurfaceView` | 解码器 → SurfaceTexture → GLES 直通 → GLSurfaceView 输出面 | 同上，正确 | 一次 GPU 拷贝 | 无色调映射 |

`GLSurfaceView` 的渲染器是**纯直通**：外部纹理采样 + 全屏 quad，不做任何画质处理
（没有超分 / 插帧 / 锐化，项目里也没有这类 GL 管线可复用）。

media3 自带的 `androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView` 看着像现成方案，
但它实现的是 `VideoDecoderOutputBufferRenderer`，只服务「软解 / 扩展渲染器吐出
`VideoDecoderOutputBuffer`」的路径，不接 `MediaCodec` 的 surface 输出，所以用不上。

实现要点（都在 `BvGLSurfaceView` 里）：

- `RENDERMODE_WHEN_DIRTY` + 帧到达脏标记，只在有新帧时 `updateTexImage` 并重绘。
- EGL 配置 `8,8,8,8,0,0`，窗口 format 保持不透明，首帧前刷黑。
- 纹理坐标约定：`samplerExternalOES` 的 `(0,0)` 在画面**左上**，所以 NDC 上边取 `t = 0`、
  下边取 `t = 1`（见 `QUAD_TEX_COORDS`）；朝向本身由 `getTransformMatrix` 处理。
  万一某台设备上画面上下颠倒，只需要把 `QUAD_TEX_COORDS` 的 t 反过来。
- 已知视频尺寸时调 `setDefaultBufferSize`，避免解码器先按 1×1 分配输出缓冲。

## 代价（必须随代码一起保留的说明）

- 每帧多一次 GPU 拷贝，**4K 或低端盒子上帧率可能下降**，所以它是可选项而不是新默认。
- 这条路径**不做 HDR 色调映射**：HLG / HDR10 / PQ 内容可能出现颜色错误，
  HDR 场景应留在 `SurfaceView`。
- 如果盒子本身没有发白问题，**保持默认 `SurfaceView` 即可（性能更好）**。

## 生命周期约束

`BvGLSurfaceView` 拥有 SurfaceTexture / Surface 的生命周期，规则是：

1. `onSurfaceCreated` 创建 `GL_TEXTURE_EXTERNAL_OES` 纹理 → `SurfaceTexture` → `Surface`，
   通过 `onVideoSurfaceAvailable` 把 Surface 交给播放器。
2. `onDetachedFromWindow` **先**让播放器把输出面置空（`onVideoSurfaceInvalidated`），
   **再**释放 Surface 与 SurfaceTexture。
3. View 被移除再加回时 `onSurfaceCreated` 会重新创建，新 Surface 必须重新交给播放器
   （回调只发给仍然有效的那一块，避免把播放器写回已释放的面）。

**不要「每次重组都 `setVideoSurface` 一次」**：同尺寸的新 Surface 会让解码器销毁并重建输出，
同时泄漏图形缓冲。所以 `AbstractVideoPlayer.setVideoSurface` 与所有调用点都是
「先 `clearVideoSurface()` 再 `setVideoSurface(surface)`」。

不同内核的等价接口：

| 内核 | 调用 |
| --- | --- |
| Media3 / ExoPlayer | `player.setVideoSurface(surface)` / `player.clearVideoSurface()` |
| IJK | `player.setSurface(surface)` |
| VLC | `vlcVout.setVideoSurface(surface, null)` |
| 直接用 MediaCodec | `configure(format, surface, ...)` |

## 验收方式

- 同一路发白频道切到 `GLSurfaceView`：颜色与 `TextureView` 一致；截图取视频区最暗值对比，
  发白时会抬到 20 以上，正常视频的纯黑区域应接近 0。
- 三个内核都能在该模式下出画（本项目目前只有 Media3）。
- 反复换台、切内核、切渲染方式不崩溃、不泄漏（可连续切换几十次观察内存）。
- 字幕、画面比例、缩放模式、音频不变（比例 / 缩放仍由 Compose 的 `Modifier.layout` 决定，
  渲染方式只负责出画）。

## 已知不在范围内的部分

- **短视频流（Shorts）**用的是独立的播放器池 + `TextureView`，不读这个设置；
  它没有 SurfaceView 直出的问题（TextureView 本来就是正确路径）。
- 播放中切换渲染方式需要**重新进入播放页**才会生效（设置项在进页时读一次）。
