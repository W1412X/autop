// FEATURE 类型：流程（列表、详情、执行记录、编辑器、AI 创建、接口登记、状态管理，docs/design/flows.md §13）。
plugins {
    id("autop.android.feature")
    id("autop.hilt")
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":domain:flow-api"))
    implementation(project(":domain:task-api"))
    implementation(project(":agent:agent-api"))
    testImplementation(project(":core:core-testing"))
}
