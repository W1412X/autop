// IMPL 类型：对话时间线的共享渲染（对话页与运行详情共用，ADR-0031）。
// 只依赖 COMMON、API 与 core-ui；不依赖任何 feature 模块。
plugins {
    id("autop.android.library")
    id("autop.hilt")
    alias(libs.plugins.kotlin.compose)
}

android {
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:core-config"))
    implementation(project(":agent:agent-api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(project(":core:core-testing"))
}
