package com.example.autop.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.kotlin.dsl.configure

/**
 * 覆盖率门禁（docs/tech/01 第 5 节；工具选择见 docs/adr/0001 补充）：
 * Kover 行覆盖率 ≥ [MIN_LINE_COVERAGE]%（bound 默认指标 LINE/COVERED_PERCENTAGE），
 * `koverVerify` 挂到 `check`。Hilt/KSP 生成类（[EXCLUDE_GENERATED_CLASSES]、
 * [EXCLUDE_GENERATED_PACKAGES]）非手写代码，从统计中排除。
 * 插件经 build-logic 类路径加载，与 KGP 同加载器，
 * 避免 plugins DSL 带来的 Kotlin Gradle Plugin 重复加载告警。
 */
class AutopCoveragePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlinx.kover")
        target.extensions.configure<KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        classes(EXCLUDE_GENERATED_CLASSES)
                        packages(EXCLUDE_GENERATED_PACKAGES)
                    }
                }
                verify {
                    rule("行覆盖率 ≥ $MIN_LINE_COVERAGE%") {
                        minBound(MIN_LINE_COVERAGE)
                    }
                }
            }
        }
        target.tasks.matching { it.name == JavaBasePlugin.CHECK_TASK_NAME }.configureEach {
            dependsOn("koverVerify")
        }
    }

    private companion object {
        const val MIN_LINE_COVERAGE = 80

        /** Hilt/Dagger 生成的 @Provides 工厂类（含内部类）。 */
        const val EXCLUDE_GENERATED_CLASSES = "*_Provide*Factory*"

        /** Hilt 聚合依赖包（每模块一个生成类）。 */
        const val EXCLUDE_GENERATED_PACKAGES = "hilt_aggregated_deps"
    }
}
