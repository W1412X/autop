// IMPL 类型（device 领域）：无障碍服务、手势、截图、等待稳定、设备类工具（docs/tech/05）。
plugins {
    id("autop.android.library")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-platform"))
    api(project(":domain:device-api"))
    implementation(project(":domain:device-core"))
    implementation(project(":domain:policy-api"))
    implementation(project(":agent:agent-api"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    // OverlayLifecycleOwner 与 ViewTree 宿主绑定（docs/tech/05 §10）。
    implementation(project(":core:core-ui"))
    implementation(platform(libs.compose.bom))
    implementation(project(":llm:llm-api"))
    // 视觉屏幕解析兜底的端侧推理运行时（docs/design/visual-screen-parsing §4/§9）。
    implementation(libs.onnxruntime.android)
    // ADB 注入通道（N199，搬运自 Shizuku）：SPAKE2 配对（BoringSSL prefab）与 TLS 密钥证书。
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.conscrypt.android)
    implementation(libs.ndk.boringssl)
    // 应用更新：GitHub Releases 检测与 APK 下载。
    implementation(libs.okhttp)

    testImplementation(project(":core:core-testing"))
    testImplementation(libs.okhttp.mockwebserver3.junit4)
    testImplementation(libs.robolectric)
}

// ADB 注入通道的 SPAKE2 配对 native（libadb.so，搬运自 Shizuku jni/）：prefab 链 BoringSSL。
android {
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    buildFeatures {
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
}

// 测试读取真实 prompt 模板、序列化规则与视觉模型资产（同 core-platform 的 autop.quirksFile 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.promptDir",
        rootProject.layout.projectDirectory
            .file("core/core-prompt-impl/src/main/assets/prompts")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.serializerRulesFile",
        layout.projectDirectory
            .file("src/main/assets/rules/serializer_rules.json")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.visionAssetDir",
        layout.projectDirectory
            .file("src/main/assets/vision")
            .asFile.absolutePath,
    )
}
