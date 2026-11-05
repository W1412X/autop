// CORE 类型（task 领域）：cron、过滤器求值、冷却限额、队列（docs/tech/07）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 RunId/RunOrigin（队列项签名）。
    api(project(":domain:task-api"))
}
