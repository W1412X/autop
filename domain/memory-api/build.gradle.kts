// API 类型：记忆模型与端口（docs/tech/06）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 Outcome/AppError、RunId（记忆写入的来源归属）。
    api(project(":core:core-common"))
}
