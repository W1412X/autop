plugins {
    `kotlin-dsl`
}

group = "com.example.autop.buildlogic"

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.android.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.androidx.room.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("autopJvmLibrary") {
            id = "autop.jvm.library"
            implementationClass = "com.example.autop.buildlogic.AutopJvmLibraryPlugin"
        }
        register("autopAndroidLibrary") {
            id = "autop.android.library"
            implementationClass = "com.example.autop.buildlogic.AutopAndroidLibraryPlugin"
        }
        register("autopAndroidFeature") {
            id = "autop.android.feature"
            implementationClass = "com.example.autop.buildlogic.AutopAndroidFeaturePlugin"
        }
        register("autopAndroidRoom") {
            id = "autop.android.room"
            implementationClass = "com.example.autop.buildlogic.AutopAndroidRoomPlugin"
        }
        register("autopAndroidApplication") {
            id = "autop.android.application"
            implementationClass = "com.example.autop.buildlogic.AutopAndroidApplicationPlugin"
        }
        register("autopHilt") {
            id = "autop.hilt"
            implementationClass = "com.example.autop.buildlogic.AutopHiltPlugin"
        }
        register("autopQuality") {
            id = "autop.quality"
            implementationClass = "com.example.autop.buildlogic.AutopQualityPlugin"
        }
        register("autopCoverage") {
            id = "autop.coverage"
            implementationClass = "com.example.autop.buildlogic.AutopCoveragePlugin"
        }
        register("autopModuleRules") {
            id = "autop.module.rules"
            implementationClass = "com.example.autop.buildlogic.ModuleRulesPlugin"
        }
    }
}
