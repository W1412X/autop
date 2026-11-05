// FEATURE 类型：任务列表、编辑、详情与运行记录（docs/tech/08 §4.3）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":domain:task-api"))
    implementation(project(":agent:agent-api"))
    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider 读取真实配置默认值（docs/tech/02 §5 的 autop.configDir 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
}
