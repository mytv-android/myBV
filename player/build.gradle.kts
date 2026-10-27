@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.BuildConfigField

plugins {
    alias(gradleLibs.plugins.android.library)
    alias(gradleLibs.plugins.compose.compiler)
}

android {
    namespace = "${AppConfiguration.namespaceId}.player"
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        minSdk = AppConfiguration.minSdk

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("r8Test") {
            isMinifyEnabled = false
        }
        create("alpha") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        targetSdk = AppConfiguration.targetSdk
    }
}

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "libVLCVersion",
            BuildConfigField("String", "\"${AppConfiguration.libVLCVersion}\"", null)
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    api(project(":player:core"))
    api(project(":player:shared"))
}