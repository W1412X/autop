// IMPL 类型（llm 领域）：端侧 embedding（bge-small-zh-v1.5 int8，ONNX Runtime CPU），
// 经 EmbeddingProviderFactory 扩展点注册为 `llm.embedding.provider = local`（ADR-0028）。
plugins {
    id("autop.android.library")
    id("autop.hilt")
}

dependencies {
    api(project(":llm:llm-api"))
    implementation(project(":core:core-common"))
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)
}

// 单测读取随包发布的模型资产：清单校验、词表解析、分词与 HuggingFace 参考结果对照。
tasks.withType<Test>().configureEach {
    systemProperty(
        "autop.embeddingDir",
        layout.projectDirectory
            .dir("src/main/assets/embedding/bge-small-zh-v1.5-int8")
            .asFile.absolutePath,
    )
}
