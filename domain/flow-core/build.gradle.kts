// CORE 类型（flow 领域）：表达式、模板、条件、目标匹配、校验、分析与解释器（docs/design/flows.md）。
plugins {
    id("autop.jvm.library")
}

dependencies {
    api(project(":domain:flow-api"))
    // 屏幕树类型（ScreenModel/ScreenNode）与节点差异集合（nodeDiffSets）：证据层与目标匹配。
    api(project(":domain:device-api"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))

    testImplementation(project(":core:core-testing"))
}
