// CORE 类型（测试支撑）：只能被 testImplementation 引用（docs/tech/01 第 4 节）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":core:core-common"))
    api(project(":core:core-config"))
    api(project(":core:core-prompt"))
    // Provider 契约测试（docs/tech/03 §5.3）：统一消息模型与本地 SSE 测试服务器。
    api(project(":llm:llm-api"))
    api(libs.okhttp.mockwebserver3)
    implementation(libs.junit)
    implementation(libs.truth)
}

// 自检测试读取真实的配置默认值与 prompt 模板（docs/tech/02 §5 的 autop.configDir/autop.promptDir 机制）。
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
