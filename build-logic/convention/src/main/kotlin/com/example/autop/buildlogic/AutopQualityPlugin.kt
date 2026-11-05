package com.example.autop.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

/**
 * 代码质量约定（docs/tech/01 第 5 节）：detekt（阈值读 config/detekt/detekt.yml）+
 * ktlint 格式化检查，全部挂到 `check`（文件行数不再硬性检查：按职责拆分、
 * 保持结构清晰即可，questions.md N176）。
 */
class AutopQualityPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("io.gitlab.arturbosch.detekt")
        target.pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        configureDetekt(target)
        configureKtlint(target)
        configureTests(target)
    }

    private fun configureDetekt(target: Project) {
        target.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            parallel = true
            config.setFrom(listOf(target.rootProject.file(DETEKT_CONFIG)))
        }
        // 搬运的第三方源码（Shizuku adb 协议，N199）保持上游原样便于对照，豁免风格检查。
        target.tasks.withType<Detekt>().configureEach { exclude(THIRD_PARTY_SOURCES) }
    }

    private fun configureKtlint(target: Project) {
        target.extensions.configure<KtlintExtension> {
            ignoreFailures.set(false)
            outputToConsole.set(true)
            // N203：Extension 级过滤对全部 ktlint 任务（check/format）生效，
            // format 任务不受 withType<BaseKtLintCheckTask> 覆盖，需在此豁免搬运源。
            filter {
                exclude(THIRD_PARTY_SOURCES)
            }
        }
        // AGP 9 内置 Kotlin 下 ktlint-gradle 自动源发现拿不到 Kotlin 源（questions.md N6），
        // 显式注入 src/**/*.kt，保证格式检查真实生效。
        target.tasks.withType<BaseKtLintCheckTask>().configureEach {
            exclude(THIRD_PARTY_SOURCES)
            source(
                target.fileTree("src") {
                    include("**/*.kt")
                    exclude(THIRD_PARTY_SOURCES)
                },
            )
        }
    }

    /**
     * AGP 9 的单元测试任务在无测试类的模块上会被 Hilt 生成目录误判为“有测试源”
     * （questions.md N7），放行“未发现测试”；模块存在真实测试时执行行为不变。
     */
    private fun configureTests(target: Project) {
        target.tasks.withType<Test>().configureEach {
            failOnNoDiscoveredTests.set(false)
        }
    }

    private companion object {
        const val DETEKT_CONFIG = "config/detekt/detekt.yml"

        /** 搬运的第三方源码目录（保持上游原样，豁免 detekt/ktlint）。 */
        val THIRD_PARTY_SOURCES = "**/impl/adb/**"
    }
}
