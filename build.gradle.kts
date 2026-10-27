buildscript {
    dependencies {
        // AGP 9 内置 Kotlin 默认使用 2.2.10；显式提到项目使用的 KGP / KSP 版本
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(gradleLibs.plugins.android.application) apply false
    alias(gradleLibs.plugins.android.library) apply false
    alias(gradleLibs.plugins.compose.compiler) apply false
    alias(gradleLibs.plugins.google.ksp) apply false
    alias(gradleLibs.plugins.kotlin.jvm) apply false
    alias(gradleLibs.plugins.kotlin.serialization) apply false
    alias(gradleLibs.plugins.versions)
}