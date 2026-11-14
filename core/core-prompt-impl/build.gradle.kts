// IMPL 类型：core-prompt 端口的 Android 实现（assets 模板加载、DataStore 用户覆盖）。
plugins {
    id("autop.android.library")
    id("autop.hilt")
}

dependencies {
    api(project(":core:core-prompt"))
    implementation(libs.androidx.datastore.core)
    implementation(project(":core:core-platform"))
}

// 测试读取真实的 assets/prompts（docs/tech/02 §5 的 autop.promptDir 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.promptDir",
        layout.projectDirectory
            .file("src/main/assets/prompts")
            .asFile.absolutePath,
    )
}
