// FEATURE 类型：我的 / 设置 / 模型 / 安全 / 高级 / 调试（docs/tech/08）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":llm:llm-api"))
    implementation(project(":agent:agent-api"))
    implementation(project(":domain:device-api"))
    implementation(project(":domain:memory-api"))
    implementation(project(":domain:policy-api"))
    implementation(project(":domain:task-api"))
    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider 读取真实的配置默认值（docs/tech/02 §5 的 autop.configDir 机制，同 core-testing）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
}
