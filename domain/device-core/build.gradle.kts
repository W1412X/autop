// CORE 模块：快照序列化、短 ID、差量、指纹与定位匹配（docs/tech/05 第 3 节）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":core:core-common"))
    api(project(":domain:device-api"))
    // CoordSpace（R1/A5）定义在 llm-api，CORE 模块依赖任意 API 模块（docs/tech/01 §4）。
    api(project(":llm:llm-api"))
}
