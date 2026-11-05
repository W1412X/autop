// FEATURE 类型：记忆浏览、编辑、最近变更（docs/tech/08 §4.4）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    api(project(":domain:memory-api"))
    testImplementation(project(":core:core-testing"))
}

// 单测用 FakeConfigProvider 读取真实配置默认值（docs/tech/02 §5 的 autop.configDir 机制，同 core-testing）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
}
