// IMPL 类型（agent 领域）：ThreadStore 的 Room 实现（events、baselines、fts、向量）。
import com.android.build.api.variant.HasUnitTest

plugins {
    id("autop.android.library")
    id("autop.android.room")
    id("autop.hilt")
}
dependencies {
    api(project(":agent:agent-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":llm:llm-api"))
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

// 迁移测试（MigrationTestHelper）从测试资产按 `<Db fqcn>/<version>.json` 读取导出 schema；
// androidx.room 插件只自动把 schema 接进 androidTest 资产，单测（Robolectric）这里把
// `schemas/` 注册为单测资产静态源目录（docs/tech/10 WP6b）。
androidComponents {
    onVariants { variant ->
        (variant as? HasUnitTest)
            ?.unitTest
            ?.sources
            ?.assets
            ?.addStaticSourceDirectory(
                layout.projectDirectory
                    .dir("schemas")
                    .asFile.absolutePath,
            )
    }
}
