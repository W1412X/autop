package com.example.autop.buildlogic.internal

import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * gradle.properties 中的项目参数（docs/tech/01 第 2 节）。模块构建文件里不出现数字，
 * 一律从这里读取。
 */
class AutopProperties(private val project: Project) {
    val rootPackage: String = prop("autop.rootPackage")
    val compileSdk: Int = prop("autop.compileSdk").toInt()
    val targetSdk: Int = prop("autop.targetSdk").toInt()
    val minSdk: Int = prop("autop.minSdk").toInt()
    val jvmTarget: Int = prop("autop.jvmTarget").toInt()
    val versionCode: Int = prop("autop.versionCode").toInt()
    val versionName: String = prop("autop.versionName")

    val javaVersion: JavaVersion = JavaVersion.toVersion(jvmTarget)

    private fun prop(key: String): String =
        project.rootProject.findProperty(key)?.toString()
            ?: error("gradle.properties 缺少必需的属性：$key（见 docs/tech/01 第 2 节）")

    /**
     * 按 docs/tech/01 第 3 节推导 namespace：
     * 模块名去掉 `-api` 后缀、`-` 换成 `.`，前面接根包；`:app` 直接用根包。
     * 例：`device-api` → `com.example.autop.device`，`feature-chat` → `com.example.autop.feature.chat`。
     */
    fun namespaceFor(modulePath: String, moduleName: String): String =
        if (modulePath == ":app") {
            rootPackage
        } else {
            rootPackage + "." + moduleName.removeSuffix("-api").replace('-', '.')
        }
}
