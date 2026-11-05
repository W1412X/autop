// FEATURE 类型：首次引导、健康检查（docs/tech/08 §4.7）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":llm:llm-api"))
    implementation(project(":domain:device-api"))
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
