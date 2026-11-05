// CORE 类型（agent 领域）：TurnLoop、ToolRouter、压缩、预算与熔断、内置控制类工具（docs/tech/04）。
plugins {
    id("autop.jvm.library")
    // WP6a 验收：行覆盖率 ≥80%（门禁配置在约定插件，工具选择见 docs/adr/0001 补充）
    id("autop.coverage")
}

dependencies {
    api(project(":agent:agent-api"))
    api(project(":llm:llm-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    implementation(project(":domain:policy-api"))
    // di/ 的 @InstallIn 注解来自 hilt-core（dagger artifact 不含），运行时由 app 提供。
    compileOnly(libs.hilt.core)
    // hilt-compiler 生成 AggregatedDeps 聚合元数据，app 的 Hilt 图才能看到 di/ 的 @InstallIn 模块（N49）。
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider/FakePromptRepository 读取真实的配置默认值与 prompt 模板
// （docs/tech/02 §5 的 autop.configDir/autop.promptDir 机制，同 core-testing）。
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
