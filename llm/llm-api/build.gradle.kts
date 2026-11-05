// API 类型：统一消息模型与 Provider 端口（docs/tech/03）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面返回 Outcome / 持有 AppError，下游需要见到类型。
    api(project(":core:core-common"))
}
