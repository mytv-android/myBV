# 可选软件 DD+ 出口

Rust 管理 JNI 句柄、生命周期和借用；最小 C 文件桥接 FFmpeg 帧 API。Kotlin Eac3AudioOutputProvider 在 DSP 后编码 5.1 PCM16，输出为 E-AC-3 bed，不提供 Atmos / JOC 对象。

默认关闭。独立动态库名 libavcodec_mybv.so / libavutil_mybv.so / libswresample_mybv.so，避免与原 FFmpeg 解码扩展冲突，不静态合并 FFmpeg。

## 重建

官方 FFmpeg 8.0.1：https://ffmpeg.org/releases/ffmpeg-8.0.1.tar.xz

SHA256：05ee0b03119b45c0bdb4df654b96802e909e0a752f72e4fe3794f487229e5a41

LGPL 2.1 或更新版，随目录保留 COPYING.LGPLv2.1。构建禁止 GPL / nonfree，只启 E-AC-3 encoder 和必要音频库。

```shell
python tools/build_ffmpeg_audio.py --source /path/ffmpeg-8.0.1 --ndk /path/ndk/28.2.13676358 --output /path/ffmpeg-android
./gradlew :app:assembleLiteDebug -Pmybv.rustAudio=true -Pmybv.eac3Encoder=true -Pmybv.ffmpegPrefix=/path/ffmpeg-android
```

需要 Rust Android targets、cargo-ndk、Python、NDK 和 Bash / make。脚本不下载源码、不装全局组件，Windows 支持 Git Bash / NDK make，可用 `--bash` 指定 Git Bash 路径，编译环境读取本机 Visual Studio。默认 arm64-v8a / armeabi-v7a，16 KiB 最大页面对齐。

生成前缀包含对应头文件、动态库、来源摘要和许可；APK 另附 LGPL 许可。可用相同 API / ABI 的修改版库重建应用，发行者应保留对应源码、构建方法和修改说明，不以应用条款限制 LGPL 组件的调试替换。不能将未知 FFmpeg 版本当固定版本打包。

本分支提供双 ARM 构建及帧头边界验证。功放 DD+ 接收、实际编码时序、同步及持续播放仍需设备验证，不将构建成功视为听感或播放链路验收。普通上混算法和比较方法见 [自适应上混](../../docs/development/stereo-upmix.md)。
