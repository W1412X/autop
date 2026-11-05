// API 类型：Tool SPI、ThreadEvent、AgentSession 与各扩展点接口（docs/tech/04）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 Outcome/AppError、ConfigSnapshot、消息模型、策略主体与模板仓库。
    api(project(":core:core-common"))
    api(project(":core:core-config"))
    api(project(":core:core-prompt"))
    api(project(":domain:policy-api"))
    api(project(":llm:llm-api"))
}
