# 可选 Rust 音频处理后端

本次改动把现有音量均衡器的 PCM 运算从 Media3 生命周期中分离，提供 Kotlin 与 Rust 两个等价后端。响度策略、元数据来源、采样率、声道数和播放时钟均由原播放器管理。

## 默认行为

- 默认构建使用 Kotlin，不要求安装 Rust 或 Android NDK。
- 音量均衡关闭时不插入增益处理器；独立空间处理也默认关闭。
- 增益后端只处理 PCM16/PCM float，保持声道数；可选空间处理器输出 5.1，详见 [双声道上混](stereo-upmix.md)。均不编码 Atmos 或生成 Dolby Vision。
- 原生库缺失、ABI 不匹配或缓冲不适用时，完整音频块交给 Kotlin 后端处理。
- 原生库在播放器创建时加载，不在音频输出回调中首次加载。
- 调试信息中的 `audio PCM backend (last used)` 显示最近使用的 PCM 处理后端，不表示压缩码流经过了处理。

## 编译启用

安装项目原有的 JDK/Android SDK、Rust stable、Android NDK，以及 `cargo-ndk`。建议使用 NDK 28.2.13676358，将其路径设为 `ANDROID_NDK_HOME`。crate 内的链接配置显式要求 64 位 Android 库使用 16 KiB 加载段对齐。不要把 Android `.so` 或开发者本机路径提交到仓库。

```shell
cargo install cargo-ndk --version 4.1.2 --locked
rustup target add aarch64-linux-android armv7-linux-androideabi
./gradlew assembleDefaultDebug "-Pmybv.rustAudio=true"
```

默认编译 `arm64-v8a,armeabi-v7a`，按系统 ABI 而非 CPU 名称选择。可以覆盖：

```shell
./gradlew assembleDefaultDebug "-Pmybv.rustAudio=true" "-Pmybv.rustAbis=arm64-v8a"
```

Windows 使用 `gradlew.bat`；PowerShell 中保留示例里构建参数的引号，避免带点的属性名被拆开。对应的 Rust target 必须事先安装；`cargo ndk` 必须在 PATH 中。使用 `--locked` 固定 Rust 依赖。生成物位于 `player/core/build/`，默认构建不会打包它们。

开启应用原有的音量均衡设置后，带有可用响度元数据的内容才会执行增益运算。无元数据时旁路，符合原行为。

## 验证

```shell
cargo test --manifest-path native/audio/Cargo.toml --locked --all-features
cargo fmt --manifest-path native/audio/Cargo.toml --check
cargo clippy --manifest-path native/audio/Cargo.toml --locked --all-features --all-targets -- -D warnings
python native/audio/tests/run_jni_smoke.py
./gradlew :player:core:testDebugUnitTest "-Pmybv.testNativeAudio=native/audio/target/release"
```

JNI 测试需要 JDK。它实际加载桌面原生库，检查偏移、非对齐缓冲、越界、别名、PCM16 全取值与浮点增益斜坡。最后一条命令加载这份宿主库，实际执行 Kotlin 适配器到 JNI 的完整链路；库未加载时会失败，不会静默跳过。CI 覆盖 Linux 与 Windows，并在 Android 构建任务中验证默认 Kotlin 后端、可选 Rust 后端和 AAR 内两种 ARM ABI 的打包。长时间播放、seek/换集、倍速与软硬解路径另需设备实测。

## 增益后端的边界约定

- JNI API 版本为 1；不兼容的二进制必须更新版本并回退。
- 调用方独占输入与可写输出缓冲，且两段内存不重叠。原生层检查容量、偏移和别名，不保留指针。
- Java 的 position/limit 由 Kotlin 管理；JNI 只处理明确传入的字节区间。
- 每个块使用一个增益参数快照，所有声道共享同一帧的增益。
- 不足完整帧的尾部字节原样复制，与原处理器一致。
- 输出削波与 PCM16 向零截断规则保持兼容。单位增益直接复制，保留 NaN payload 和尾部字节。
- 常规处理不分配原生内存、不创建线程、不执行 I/O。不能让 panic 穿越 JNI。
- 任何失败都由返回值表示；Kotlin 使用原输入完整重算，不消费部分输出。

## 后续音画处理的拆分

空间上混现放入独立、有状态的处理器，格式、延迟、能力与验证边界见 [双声道上混](stereo-upmix.md)。音量增益后端仍保持原来的逐样本语义。HDR 着色器和声源分离不属于本次改动。

Atmos 需要独立编码与直出后端；Dolby Vision 需要有效的编码/元数据及播放链路。PCM 处理接口不代表设备具备这两种编码能力。没有通过目标设备验证前，不增加声称已支持的产品开关。

建议将后续 PR 分成：硬件能力诊断、GPU HDR 渲染、带延迟契约的空间音频处理、经过设备验证的编码输出。这样可以独立审核行为和性能，也便于定位回归。
