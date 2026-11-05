// IMPL 类型：core-config 端口的 Android 实现（assets 加载、DataStore 用户覆盖）。
plugins {
    id("autop.android.library")
    id("autop.hilt")
}

dependencies {
    api(project(":core:core-config"))
    // ModelProfileStore 端口（llm 领域 API）的存储实现在本模块（docs/tech/03 §3）。
    implementation(project(":llm:llm-api"))
    implementation(libs.androidx.datastore.core)
}

// 一致性测试读取真实的 assets/config；规则加载测试读取真实的 assets/rules（docs/tech/02 §5 的 autop.configDir 机制）。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.configDir",
        layout.projectDirectory
            .file("src/main/assets/config")
            .asFile.absolutePath,
    )
    systemProperty(
        "autop.policyRulesFile",
        layout.projectDirectory
            .file("src/main/assets/rules/policy_rules.json")
            .asFile.absolutePath,
    )
}
