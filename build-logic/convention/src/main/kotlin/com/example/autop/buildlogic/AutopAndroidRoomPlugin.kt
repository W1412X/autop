package com.example.autop.buildlogic

import androidx.room.gradle.RoomExtension
import com.example.autop.buildlogic.internal.CatalogDeps
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Room 模块约定（docs/tech/01 第 5 节）：KSP + Room runtime/ktx/compiler +
 * `room { schemaDirectory("$projectDir/schemas") }`（schema 导出与提交）+ Room 测试依赖。
 */
class AutopAndroidRoomPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        if (!target.pluginManager.hasPlugin(ANDROID_LIBRARY_ID) &&
            !target.pluginManager.hasPlugin(ANDROID_APPLICATION_ID)
        ) {
            target.pluginManager.apply("autop.android.library")
        }
        target.pluginManager.apply("com.google.devtools.ksp")
        target.pluginManager.apply("androidx.room")
        // schemaDirectory 有多个重载，这里用 String 重载（签名经 javap 确认）。
        target.extensions.configure<RoomExtension> {
            schemaDirectory("${target.projectDir}/schemas")
        }
        addDependencies(target)
    }

    private fun addDependencies(target: Project) {
        val deps = CatalogDeps(target)
        listOf("androidx-room-runtime", "androidx-room-ktx").forEach { deps.add("implementation", it) }
        deps.add("ksp", "androidx-room-compiler")
        // androidx-sqlite-framework：迁移测试包装 SupportSQLiteOpenHelper.Factory 时需编译期可见。
        listOf(
            "androidx-room-testing",
            "androidx-test-core",
            "robolectric",
            "androidx-sqlite-framework",
        ).forEach { deps.add("testImplementation", it) }
        deps.add("androidTestImplementation", "androidx-room-testing")
    }

    private companion object {
        const val ANDROID_LIBRARY_ID = "com.android.library"
        const val ANDROID_APPLICATION_ID = "com.android.application"
    }
}
