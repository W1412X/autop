// CORE 类型（llm 领域）：适配器共享的传输层（OkHttp 工厂、SSE 解析、重试）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":llm:llm-api"))
    api(libs.okhttp)
    implementation(project(":core:core-config"))
    // di/ 的 @InstallIn 注解来自 hilt-core（dagger artifact 不含），运行时由 app 提供。
    compileOnly(libs.hilt.core)
    // hilt-compiler 生成 AggregatedDeps 聚合元数据，app 的 Hilt 图才能看到 di/ 的 @InstallIn 模块（N49）。
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:core-testing"))
    testImplementation(libs.okhttp.mockwebserver3.junit4)
}

// 测试用 FakeConfigProvider 读取真实的配置默认值（同 core-config-impl 的 autop.configDir 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
}
