// IMPL 类型（task 领域）：调度、闹钟、前台服务 AgentHost、触发源实现（docs/tech/07）。
import com.android.build.api.variant.HasUnitTest

plugins {
    id("autop.android.library")
    id("autop.android.room")
    id("autop.hilt")
}

dependencies {
    api(project(":domain:task-api"))
    implementation(project(":domain:task-core"))
    implementation(project(":domain:device-api"))
    implementation(project(":agent:agent-api"))
    implementation(project(":domain:memory-api"))
    implementation(project(":domain:flow-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    implementation(project(":llm:llm-api"))
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider 读取真实配置默认值（docs/tech/02 §5 的 autop.configDir 机制）；
// FakePromptRepository 同理读真实模板目录（autop.promptDir，如中断恢复的 agent.resume.after_interrupt）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.promptDir",
        rootProject.layout.projectDirectory
            .file("core/core-prompt-impl/src/main/assets/prompts")
            .asFile.absolutePath,
    )
}

// 迁移测试（MigrationTestHelper）从测试资产按 `<Db fqcn>/<version>.json` 读取导出 schema；
// androidx.room 插件只自动把 schema 接进 androidTest 资产，单测（Robolectric）这里把
// `schemas/` 注册为单测资产静态源目录（docs/tech/10 WP9d，与 agent-store 同构）。
androidComponents {
    onVariants { variant ->
        (variant as? HasUnitTest)
            ?.unitTest
            ?.sources
            ?.assets
            ?.addStaticSourceDirectory(
                layout.projectDirectory
                    .dir("schemas")
                    .asFile.absolutePath,
            )
    }
}
