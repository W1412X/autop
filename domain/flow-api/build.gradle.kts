// API 类型：流程文档模型、存储/执行/AI 生成端口（docs/design/flows.md）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 RunHandle/RunOutcome、ConfigSnapshot、Outcome 与 Id。
    api(project(":agent:agent-api"))
    api(project(":core:core-common"))
    api(project(":core:core-config"))
    api(project(":domain:device-api"))
}
