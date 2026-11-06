// IMPL 类型（flow 领域）：flows.db、端口实现、FlowSession、run_flow/list_flows 工具、AI 创作（docs/design/flows.md）。
import com.android.build.api.variant.HasUnitTest

plugins {
    id("autop.android.library")
    id("autop.android.room")
    id("autop.hilt")
}

dependencies {
    api(project(":domain:flow-api"))
    implementation(project(":domain:flow-core"))
    implementation(project(":domain:device-api"))
    implementation(project(":agent:agent-api"))
    implementation(project(":domain:memory-api"))
    // AI 示范通过 RunService 发起智能体 Run。
    implementation(project(":domain:task-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    implementation(project(":llm:llm-api"))
    // 流程 API 节点自带 OkHttp 客户端（域名白名单、不依赖 llm-http）。
    implementation(libs.okhttp)

    testImplementation(project(":core:core-testing"))
}

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

// 迁移测试从测试资产读取导出 schema（与 task-impl 同构）。
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
