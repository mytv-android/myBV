@file:Suppress("UnstableApiUsage")

plugins {
    alias(gradleLibs.plugins.android.library)
    alias(gradleLibs.plugins.compose.compiler)
}

abstract class RustAudioBuildTask : Exec() {
    @get:OutputDirectory
    abstract val jniLibsDirectory: org.gradle.api.file.DirectoryProperty
}

// 可选后端：默认构建不需要 Rust/NDK，也不打包或加载原生处理库。
val enableRustAudio = providers.gradleProperty("mybv.rustAudio")
    .map { it.toBooleanStrict() }
    .orElse(false)
val enableEac3Encoder = providers.gradleProperty("mybv.eac3Encoder")
    .map { it.toBooleanStrict() }.orElse(false)
val rustAudioAbis = providers.gradleProperty("mybv.rustAbis")
    .orElse("arm64-v8a,armeabi-v7a")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty).distinct() }
val rustAudioJniDir = layout.buildDirectory.dir(
    "generated/rustAudio/${rustAudioAbis.get().joinToString("-")}/jniLibs"
)

android {
    namespace = "${AppConfiguration.namespaceId}.player.core"
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        minSdk = AppConfiguration.minSdk
        buildConfigField("boolean", "ENABLE_RUST_AUDIO", enableRustAudio.get().toString())
        buildConfigField("boolean", "ENABLE_EAC3_ENCODER", enableEac3Encoder.get().toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("r8Test") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("alpha") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

if (enableEac3Encoder.get()) {
    require(enableRustAudio.get()) { "mybv.eac3Encoder requires mybv.rustAudio=true" }
    val prefix = providers.gradleProperty("mybv.ffmpegPrefix").orNull
        ?: error("mybv.eac3Encoder requires mybv.ffmpegPrefix; see native/encode/README.md")
    val buildEncoder = tasks.register<RustAudioBuildTask>("buildEac3Encoder") {
        val abis = rustAudioAbis.get()
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "armeabi-v7a") })
        val destination = layout.buildDirectory.dir("generated/eac3Encoder/${abis.joinToString("-")}/jniLibs")
        jniLibsDirectory.set(destination)
        inputs.files(rootProject.fileTree("native/encode") {
            include("Cargo.toml", "Cargo.lock", ".cargo/config.toml", "build.rs", "src/**")
        })
        inputs.file(rootProject.file("tools/build_eac3_jni.py"))
        inputs.property("abis", abis)
        inputs.files(rootProject.fileTree(rootProject.file(prefix)) {
            abis.forEach { abi -> include("$abi/include/**", "$abi/lib/*.so", "$abi/SOURCE.txt") }
        })
        workingDir(rootProject.projectDir)
        commandLine(listOf("python", rootProject.file("tools/build_eac3_jni.py").absolutePath,
            "--prefix", rootProject.file(prefix).absolutePath,
            "--output", destination.get().asFile.absolutePath,
            "--target", layout.buildDirectory.dir("eac3EncoderTarget").get().asFile.absolutePath,
            "--abis") + abis)
    }
    androidComponents.onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildEncoder) { it.jniLibsDirectory }
    }
}

if (enableRustAudio.get()) {
    val buildRustAudio = tasks.register<RustAudioBuildTask>("buildRustAudio") {
        group = "build"
        description = "Build the optional Rust PCM kernel with cargo-ndk"
        val abis = rustAudioAbis.get()
        require(abis.isNotEmpty()) { "mybv.rustAbis must contain at least one Android ABI" }
        require(abis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }) {
            "Unsupported ABI in mybv.rustAbis: $abis"
        }
        // cargo-ndk 在当前目录运行 metadata，需从 crate 根目录启动。
        workingDir(rootProject.file("native/audio"))
        inputs.files(rootProject.fileTree("native/audio") {
            include("Cargo.toml", "Cargo.lock", ".cargo/config.toml", "src/**/*.rs")
        })
        inputs.property("abis", abis)
        inputs.property("minSdk", AppConfiguration.minSdk)
        jniLibsDirectory.set(rustAudioJniDir)
        environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rustAudioTarget").get().asFile.absolutePath)
        commandLine(
            listOf("cargo", "ndk", "--platform", AppConfiguration.minSdk.toString()) +
                abis.flatMap { listOf("-t", it) } +
                listOf(
                    "-o", rustAudioJniDir.get().asFile.absolutePath,
                    "build", "--release", "--locked", "--features", "jni-bridge"
                )
        )
    }
    // 使用 AGP Variant API 注册生成的 JNI 源目录，同时建立任务依赖。
    androidComponents.onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRustAudio) { it.jniLibsDirectory }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    implementation(androidx.activity.compose)
    implementation(androidx.compose.material)
    implementation(androidx.compose.ui)
    implementation(androidx.compose.ui.tooling.preview)
    implementation(androidx.compose.ui.util)
    implementation(androidx.core.ktx)
    implementation(androidx.media3.common)
    implementation(androidx.media3.datasource.okhttp)
    implementation(androidx.media3.decoder)
    implementation(androidx.media3.exoplayer)
    implementation(androidx.media3.exoplayer.hls)
    implementation(androidx.media3.ui)
    implementation(libs.logging)
    implementation(libs.material)
    implementation(project(":libs:ffmpegDecoder"))
    implementation(project(":player:shared"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    androidTestImplementation(androidx.compose.ui.test.junit4)
    debugImplementation(androidx.compose.ui.test.manifest)
    debugImplementation(androidx.compose.ui.tooling)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 测试时可加载桌面宿主库，实际执行 Kotlin -> JNI -> Rust，而非仅验证回退。
    providers.gradleProperty("mybv.testNativeAudio").orNull?.let { directory ->
        systemProperty("java.library.path", rootProject.file(directory).absolutePath)
        systemProperty("mybv.requireNativeAudio", "true")
        inputs.files(rootProject.fileTree(directory) {
            include("mybv_audio.dll", "libmybv_audio.so", "libmybv_audio.dylib")
        }).withPropertyName("hostAudioLibrary").withPathSensitivity(PathSensitivity.NONE)
    }
}
