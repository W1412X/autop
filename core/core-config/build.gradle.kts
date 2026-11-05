// API 类型：只允许依赖 COMMON 与 API（docs/tech/01 第 4 节）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":core:core-common"))
}
