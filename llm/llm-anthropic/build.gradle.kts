// IMPL 类型（llm 领域）：Anthropic 兼容协议适配器。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":llm:llm-http"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-prompt"))
    implementation(libs.kotlinx.serialization.json)
    // di/ 的 @InstallIn 注解来自 hilt-core（dagger artifact 不含），运行时由 app 提供。
    compileOnly(libs.hilt.core)
    // hilt-compiler 生成 AggregatedDeps 聚合元数据，app 的 Hilt 图才能看到 di/ 的 @InstallIn 模块（N49）。
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:core-testing"))
    testImplementation(libs.okhttp.mockwebserver3.junit4)
}
