package com.example.autop.buildlogic

import com.example.autop.buildlogic.internal.AutopProperties
import com.example.autop.buildlogic.internal.CatalogDeps
import com.example.autop.buildlogic.internal.ModuleType
import com.example.autop.buildlogic.internal.ModuleTypes
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * 纯 Kotlin（JVM）库模块约定（docs/tech/01 第 5 节）：
 * Kotlin + 序列化 + Java 目标 17 + api 模块 explicitApi + Dagger 注解处理（供 @Inject 构造生成 Factory，
 * 业务代码只用 javax.inject）+ quality + 依赖规则检查 + 统一测试依赖。
 */
class AutopJvmLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlin.jvm")
        target.pluginManager.apply("java-library")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        target.pluginManager.apply("com.google.devtools.ksp")
        target.pluginManager.apply("autop.quality")
        target.pluginManager.apply("autop.module.rules")

        val props = AutopProperties(target)
        configureJava(target, props)
        configureKotlin(target, props)
        addDependencies(target)
    }

    private fun configureJava(target: Project, props: AutopProperties) {
        target.extensions.configure<JavaPluginExtension> {
            sourceCompatibility = props.javaVersion
            targetCompatibility = props.javaVersion
        }
    }

    private fun configureKotlin(target: Project, props: AutopProperties) {
        val kotlin = target.extensions.getByType(KotlinJvmProjectExtension::class.java)
        kotlin.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(props.jvmTarget.toString()))
        if (ModuleTypes.infer(target.path).type == ModuleType.API) {
            kotlin.explicitApi()
        }
    }

    private fun addDependencies(target: Project) {
        val deps = CatalogDeps(target)
        BASE_ALIASES.forEach { deps.add("api", it) }
        // 生成 @Inject 构造的 Factory 供 Hilt 图使用；dagger 仅编译期可见，运行时由 app 提供。
        deps.add("compileOnly", "dagger")
        deps.add("ksp", "dagger-compiler")
        TEST_ALIASES.forEach { deps.add("testImplementation", it) }
    }

    private companion object {
        val BASE_ALIASES = listOf("kotlinx-coroutines-core", "kotlinx-serialization-json", "javax-inject")
        val TEST_ALIASES = listOf("junit", "kotlinx-coroutines-test", "turbine", "truth")
    }
}
