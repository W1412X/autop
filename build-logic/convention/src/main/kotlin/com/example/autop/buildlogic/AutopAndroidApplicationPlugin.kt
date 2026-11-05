package com.example.autop.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.example.autop.buildlogic.internal.AutopProperties
import com.example.autop.buildlogic.internal.CatalogDeps
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * app 模块约定（docs/tech/01 第 5 节）：com.android.application + applicationId 与版本号
 * （读 gradle.properties）+ Compose + 单测 / 仪器测试依赖 + quality + 依赖规则检查。
 */
class AutopAndroidApplicationPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        target.pluginManager.apply("autop.quality")
        target.pluginManager.apply("autop.module.rules")

        val props = AutopProperties(target)
        target.extensions.configure<ApplicationExtension> {
            namespace = props.rootPackage
            compileSdk = props.compileSdk
            enableKotlin = true
            defaultConfig.applicationId = props.rootPackage
            defaultConfig.minSdk = props.minSdk
            defaultConfig.targetSdk = props.targetSdk
            defaultConfig.versionCode = props.versionCode
            defaultConfig.versionName = props.versionName
            defaultConfig.testInstrumentationRunner = TEST_RUNNER
            // 体积：只打真机 ABI（x86/x86_64 为模拟器专用，约占 78MB 纯浪费）。
            defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            compileOptions.sourceCompatibility = props.javaVersion
            compileOptions.targetCompatibility = props.javaVersion
            buildFeatures.compose = true
            testOptions.unitTests.isIncludeAndroidResources = true
            // 单测可用 Context 等 android 类的空实现（WP11：ViewModel 注入 @ApplicationContext）。
            testOptions.unitTests.isReturnDefaultValues = true
        }
        addDependencies(target)
    }

    private fun addDependencies(target: Project) {
        val deps = CatalogDeps(target)
        listOf("kotlinx-coroutines-android", "kotlinx-serialization-json", "javax-inject").forEach {
            deps.add("implementation", it)
        }
        listOf("junit", "kotlinx-coroutines-test", "turbine", "truth").forEach {
            deps.add("testImplementation", it)
        }
        listOf("androidx-junit", "androidx-espresso-core").forEach {
            deps.add("androidTestImplementation", it)
        }
    }

    private companion object {
        const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
    }
}
