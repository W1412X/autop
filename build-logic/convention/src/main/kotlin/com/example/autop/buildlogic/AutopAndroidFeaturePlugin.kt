package com.example.autop.buildlogic

import com.android.build.api.dsl.LibraryExtension
import com.example.autop.buildlogic.internal.CatalogDeps
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * feature 模块约定（docs/tech/01 第 5 节）：在 android library 基础上接入 Compose、
 * 依赖 core-ui 与统一的导航 / ViewModel / Hilt-Compose 依赖。
 */
class AutopAndroidFeaturePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("autop.android.library")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val deps = CatalogDeps(target)
        target.extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }
        deps.addPlatform("implementation", "compose-bom")
        COMPOSE_ALIASES.forEach { deps.addNoVersion("implementation", it) }
        deps.addNoVersion("debugImplementation", "compose-ui-tooling")
        NAV_ALIASES.forEach { deps.add("implementation", it) }
        deps.addProject("implementation", CORE_UI_PATH)
    }

    private companion object {
        const val CORE_UI_PATH = ":core:core-ui"
        val COMPOSE_ALIASES = listOf(
            "compose-ui", "compose-foundation", "compose-material3",
            "compose-material-icons-extended", "compose-ui-tooling-preview",
        )
        val NAV_ALIASES = listOf(
            "androidx-lifecycle-viewmodel-compose", "androidx-lifecycle-runtime-compose",
            "androidx-navigation-compose", "hilt-navigation-compose",
        )
    }
}
