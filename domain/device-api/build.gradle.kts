// API 类型：快照模型与设备端口（docs/tech/05）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    // 公开面持有 Outcome/AppError。
    api(project(":core:core-common"))
}
