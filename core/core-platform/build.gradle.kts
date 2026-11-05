// IMPL 类型：平台能力（SecretStore、Logger 实现、PlatformCapabilities、DeviceQuirks）。
plugins {
    id("autop.android.library")
    id("autop.hilt")
}

dependencies {
    api(project(":core:core-common"))
    implementation(project(":core:core-config"))
    // BitmapImageLoader 实现 llm 领域的 ImageLoader 端口（docs/tech/03 §6）。
    implementation(project(":llm:llm-api"))
    implementation(libs.androidx.datastore.preferences)
}

// 测试读取真实的 assets/rules/device_quirks.json（同 core-config-impl 的 autop.configDir 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.quirksFile",
        layout.projectDirectory
            .file("src/main/assets/rules/device_quirks.json")
            .asFile.absolutePath,
    )
}
