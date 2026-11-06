// API 类型：任务、触发与运行端口（docs/tech/07）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 AgentEvent/RunState/PauseReason、Outcome/Id 与 PolicyOverrides。
    api(project(":agent:agent-api"))
    api(project(":core:core-common"))
    api(project(":domain:policy-api"))
    // 任务可绑定流程（Task.flow）；运行流程需要 FlowRunOptions。
    api(project(":domain:flow-api"))
}
