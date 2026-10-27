import com.android.build.api.variant.BuildConfigField
import java.net.URI

plugins {
    alias(gradleLibs.plugins.android.library)
    alias(gradleLibs.plugins.compose.compiler)
    alias(gradleLibs.plugins.google.ksp)
    alias(gradleLibs.plugins.google.protobuf)
    alias(gradleLibs.plugins.google.services) apply false
    alias(gradleLibs.plugins.kotlin.serialization)
}

android {
    namespace = AppConfiguration.namespaceId
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        minSdk = AppConfiguration.minSdk
        vectorDrawables {
            useSupportLibrary = true
        }
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

    testOptions {
        targetSdk = AppConfiguration.targetSdk
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.apply {
            put("VERSION_CODE", BuildConfigField("int", AppConfiguration.versionCode, null))
            put("VERSION_NAME", BuildConfigField("String", "\"${AppConfiguration.versionName}\"", null))
            put("APPLICATION_ID", BuildConfigField("String", "\"${AppConfiguration.appId}\"", null))
            put(
                "MYBV_CERT_SHA256",
                BuildConfigField("String", "\"${AppConfiguration.myBvCertSha256}\"", null)
            )
            put(
                "BLACKLIST_URL",
                BuildConfigField("String", "\"${AppConfiguration.blacklistUrl}\"", null)
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    annotationProcessor(androidx.room.compiler)
    ksp(androidx.room.compiler)
    ksp(libs.koin.ksp.compiler)
    api(platform("${libs.firebase.bom.get()}"))
    api(androidx.activity.compose)
    api(androidx.core.ktx)
    api(androidx.core.splashscreen)
    api(androidx.compose.constraintlayout)
    api(androidx.compose.ui)
    api(androidx.compose.ui.util)
    api(androidx.compose.ui.tooling.preview)
    api(androidx.compose.material.icons)
    api(androidx.compose.material)
    api(androidx.compose.material3)
    api(libs.material.kolor)
    api(androidx.compose.material3.adaptive)
    api(androidx.compose.material3.adaptive.layout)
    api(androidx.compose.material3.adaptive.navigation)
    api(androidx.compose.material3.adaptive.navigation.suit)
    api(androidx.compose.material3.window.size)
    api(androidx.datastore.typed)
    api(androidx.datastore.preferences)
    api(androidx.lifecycle.runtime.ktx)
    api(androidx.media3.common)
    api(androidx.media3.decoder)
    api(androidx.media3.exoplayer)
    api(androidx.media3.ui)
    api(androidx.navigation.compose)
    api(androidx.room.ktx)
    api(androidx.room.runtime)
    api(androidx.webkit)
    api(libs.accompanist.systemuicontroller)
    api(libs.coil.compose)
    api(libs.coil.gif)
    api(libs.coil.svg)
    api(libs.firebase.analytics)
    api(libs.firebase.crashlytics)
    api(libs.geetest.sensebot)
    api(libs.koin.android)
    api(libs.koin.annotations)
    api(libs.koin.compose)
    api(libs.koin.compose.navigation)
    api(libs.kotlinx.serialization)
    api(libs.ktor.client.cio)
    api(libs.koin.core)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.client.core)
    api(libs.ktor.client.encoding)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.serialization.kotlinx)
    api(libs.ktor.server.cio)
    api(libs.ktor.server.core)
    api(libs.logging)
    api(libs.lottie)
    api(libs.material)
    api(libs.protobuf.kotlin)
    api(libs.qrcode)
    api(libs.rememberPreference)
    api(libs.slf4j.android.mvysny)
    api(libs.zxing)
    api(project(mapOf("path" to ":bili-api")))
    api(project(mapOf("path" to ":bili-subtitle")))
    api(project(mapOf("path" to ":player")))
    api(project(mapOf("path" to ":utils")))
    api(project(mapOf("path" to ":symbols")))
    testImplementation(androidx.room.testing)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(androidx.compose.ui.test.junit4)
    debugApi(androidx.compose.ui.test.manifest)
    debugApi(androidx.compose.ui.tooling)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
                create("kotlin")
            }
        }
    }
}

tasks.register("downloadBlacklist") {
    val assetsDir = file("src/main/res/raw")
    val resourceUrl = AppConfiguration.blacklistUrl
    val outputFile = File(assetsDir, "blacklist.bin")

    if (outputFile.exists()) return@register

    doLast {
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }
        println("Downloading resource from $resourceUrl to ${outputFile.absolutePath}")
        URI(resourceUrl).toURL().openStream().use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
            println("Download complete: ${outputFile.absolutePath}")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("downloadBlacklist")
}