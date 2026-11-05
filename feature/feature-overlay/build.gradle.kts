// FEATURE 类型：悬浮球与迷你面板的 Compose 内容（docs/tech/08 第 5 节）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":agent:agent-api"))
    implementation(project(":domain:device-api"))
    implementation(project(":domain:task-api"))
    testImplementation(project(":core:core-testing"))
    // ViewTree owner 注入回归（N177）：Robolectric 跑窗口 attach 场景。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
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
