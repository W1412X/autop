// IMPL 类型（memory 领域）：存储、FTS、向量、混合检索、记忆工具与 ContextContributor（docs/tech/06）。
plugins {
    id("autop.android.library")
    id("autop.android.room")
    id("autop.hilt")
}
dependencies {
    api(project(":domain:memory-api"))
    api(project(":agent:agent-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    implementation(project(":llm:llm-api"))
    implementation(project(":domain:device-api"))
    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider/FakePromptRepository 读取真实配置与模板（docs/tech/02 §5 机制）。
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
