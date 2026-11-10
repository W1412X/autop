// API 类型：策略端口（docs/tech/06 第 6 节）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // PolicyEngine.evaluate 的 Run 配置快照参数（WP7.2）。
    api(project(":core:core-config"))
}
