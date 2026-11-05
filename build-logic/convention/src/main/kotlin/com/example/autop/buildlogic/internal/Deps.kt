package com.example.autop.buildlogic.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * 约定插件里按版本目录别名添加依赖。
 * 版本唯一来源是 `gradle/libs.versions.toml`，模块构建文件与插件代码里都不出现版本号。
 */
class CatalogDeps(private val project: Project) {
    private val catalog =
        project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

    /** 别名对应的 `group:name:version` 坐标。 */
    fun coords(alias: String): String {
        val dep = find(alias)
        return "${dep.group}:${dep.name}:${dep.versionConstraint.requiredVersion}"
    }

    /** 别名对应的 `group:name` 坐标（无版本，交给 BOM 管理）。 */
    fun coordsNoVersion(alias: String): String {
        val dep = find(alias)
        return "${dep.group}:${dep.name}"
    }

    fun add(configuration: String, alias: String) {
        project.dependencies.add(configuration, coords(alias))
    }

    fun addNoVersion(configuration: String, alias: String) {
        project.dependencies.add(configuration, coordsNoVersion(alias))
    }

    fun addPlatform(configuration: String, alias: String) {
        project.dependencies.add(configuration, project.dependencies.platform(coords(alias)))
    }

    fun addProject(configuration: String, path: String) {
        project.dependencies.add(configuration, project.dependencies.project(mapOf("path" to path)))
    }

    private fun find(alias: String) = catalog.findLibrary(alias)
        .orElseThrow { IllegalArgumentException("libs.versions.toml 缺少条目：$alias") }
        .get()
}
