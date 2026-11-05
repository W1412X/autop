// CORE 类型（policy 领域）：规则引擎与规则类型实现（docs/tech/06 第 6 节）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":domain:policy-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    // di/ 的 @InstallIn 注解来自 hilt-core（dagger artifact 不含），运行时由 app 提供。
    compileOnly(libs.hilt.core)
    // hilt-compiler 生成 AggregatedDeps 聚合元数据，app 的 Hilt 图才能看到 di/ 的 @InstallIn 模块（N49）。
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:core-testing"))
}

// 测试用 FakeConfigProvider 读取真实配置默认值，并读取真实的内置规则文件（同 SerializerRulesAssetTest 的机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/config")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.policyRulesFile",
        rootProject.layout.projectDirectory
            .file("core/core-config-impl/src/main/assets/rules/policy_rules.json")
            .asFile.absolutePath,
    )
}
