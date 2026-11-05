package com.example.autop.buildlogic

import com.example.autop.buildlogic.internal.CatalogDeps
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Hilt 接入约定（docs/tech/01 第 5 节）：KSP + Hilt 插件与依赖（Android 模块）。
 * 业务代码只用 `javax.inject.Inject` 构造注入（架构规范 5.1），绑定只写在 impl 模块的 `di/` 包与 app。
 */
class AutopHiltPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("com.google.devtools.ksp")
        target.pluginManager.apply("com.google.dagger.hilt.android")

        val deps = CatalogDeps(target)
        deps.add("implementation", "hilt-android")
        deps.add("ksp", "hilt-compiler")
        deps.add("api", "javax-inject")
    }
}
