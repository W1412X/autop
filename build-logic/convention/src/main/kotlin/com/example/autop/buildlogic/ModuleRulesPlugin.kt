package com.example.autop.buildlogic

import com.example.autop.buildlogic.internal.ModuleInfo
import com.example.autop.buildlogic.internal.ModuleTypes
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * 依赖规则检查（docs/tech/01 第 4 节）。在 `afterEvaluate` 中按模块类型矩阵校验
 * `implementation` / `api` / `compileOnly` 里的工程依赖，违规抛 [GradleException]。
 */
class ModuleRulesPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.afterEvaluate { checkRules(this) }
    }

    private fun checkRules(project: Project) {
        val from = ModuleTypes.infer(project.path)
        checkPluginConsistency(project, from)
        val violations = mainConfigurationsOf(project).flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).mapNotNull { dep ->
                checkDependency(from, dep)
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.distinct().joinToString(separator = "\n"))
        }
    }

    private fun checkDependency(from: ModuleInfo, dep: ProjectDependency): String? {
        if (dep.path == ModuleTypes.CORE_TESTING_PATH) {
            return "模块规则违反：${from.path}(${from.type}) 不能依赖 ${dep.path}，" +
                "core-testing 只能出现在 testImplementation，见 docs/tech/01 第 4 节"
        }
        val to = ModuleTypes.infer(dep.path)
        return if (ModuleTypes.isAllowed(from, to)) {
            null
        } else {
            "模块规则违反：${from.path}(${from.type}) 不能依赖 ${to.path}(${to.type})，见 docs/tech/01 第 4 节"
        }
    }

    /** 纯 JVM 类型（COMMON/API/CORE）必须用 autop.jvm.library，且不得应用 Android 插件。 */
    private fun checkPluginConsistency(project: Project, from: ModuleInfo) {
        if (!ModuleTypes.isJvmOnlyType(from.type)) return
        val androidPlugins = listOf("com.android.library", "com.android.application")
        if (androidPlugins.any { project.pluginManager.hasPlugin(it) }) {
            throw GradleException(
                "模块规则违反：${from.path}(${from.type}) 是纯 JVM 类型，不能应用 Android 插件，见 docs/tech/01 第 4 节",
            )
        }
        if (!project.pluginManager.hasPlugin(JVM_LIBRARY_PLUGIN_ID)) {
            throw GradleException(
                "模块规则违反：${from.path}(${from.type}) 是纯 JVM 类型，必须应用 $JVM_LIBRARY_PLUGIN_ID，见 docs/tech/01 第 4 节",
            )
        }
    }

    private fun mainConfigurationsOf(project: Project): List<org.gradle.api.artifacts.Configuration> =
        MAIN_CONFIGURATIONS.mapNotNull { project.configurations.findByName(it) }

    private companion object {
        const val JVM_LIBRARY_PLUGIN_ID = "autop.jvm.library"
        val MAIN_CONFIGURATIONS = listOf("implementation", "api", "compileOnly")
    }
}
