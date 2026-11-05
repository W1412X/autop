package com.example.autop.buildlogic

import com.android.build.api.dsl.LibraryExtension
import com.example.autop.buildlogic.internal.AutopProperties
import com.example.autop.buildlogic.internal.CatalogDeps
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android 库模块约定（docs/tech/01 第 5 节）：com.android.library + namespace 生成 +
 * SDK 版本 + Java 17 + 序列化 + AGP 9 内置 Kotlin + 单测依赖 + quality + 依赖规则检查。
 */
class AutopAndroidLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        target.pluginManager.apply("autop.quality")
        target.pluginManager.apply("autop.module.rules")

        val props = AutopProperties(target)
        target.extensions.configure<LibraryExtension> {
            namespace = props.namespaceFor(target.path, target.name)
            compileSdk = props.compileSdk
            enableKotlin = true
            defaultConfig.minSdk = props.minSdk
            defaultConfig.testInstrumentationRunner = TEST_RUNNER
            compileOptions.sourceCompatibility = props.javaVersion
            compileOptions.targetCompatibility = props.javaVersion
            testOptions.unitTests.isIncludeAndroidResources = true
            // 单测可用 Context 等 android 类的空实现（WP11：ViewModel 注入 @ApplicationContext）。
            testOptions.unitTests.isReturnDefaultValues = true
        }
        addDependencies(target)
    }

    private fun addDependencies(target: Project) {
        val deps = CatalogDeps(target)
        BASE_ALIASES.forEach { deps.add("api", it) }
        TEST_ALIASES.forEach { deps.add("testImplementation", it) }
        INSTRUMENTED_ALIASES.forEach { deps.add("androidTestImplementation", it) }
    }

    private companion object {
        const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        val BASE_ALIASES = listOf("kotlinx-coroutines-android", "kotlinx-serialization-json", "javax-inject")
        val TEST_ALIASES = listOf("junit", "kotlinx-coroutines-test", "turbine", "truth")
        val INSTRUMENTED_ALIASES = listOf("androidx-junit", "androidx-espresso-core")
    }
}
