// APP 类型：Application、MainActivity、导航、DI 组装（docs/tech/01 第 3 节）。
import java.util.Properties

plugins {
    id("autop.android.application")
    id("autop.hilt")
}

// release 签名：口令在 keystore.properties（已 gitignore，不入库）；缺省该文件时 release 出未签名包。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystore: Properties? =
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { stream -> Properties().apply { load(stream) } }
    } else {
        null
    }

android {
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-config"))
    implementation(project(":core:core-config-impl"))
    implementation(project(":core:core-platform"))
    implementation(project(":core:core-prompt-impl"))
    implementation(project(":core:core-ui"))
    implementation(project(":llm:llm-http"))
    implementation(project(":llm:llm-openai"))
    implementation(project(":llm:llm-anthropic"))
    implementation(project(":llm:llm-onnx"))
    implementation(project(":agent:agent-runtime"))
    implementation(project(":agent:agent-store"))
    implementation(project(":domain:device-impl"))
    implementation(project(":domain:flow-impl"))
    implementation(project(":domain:memory-impl"))
    implementation(project(":domain:policy-core"))
    implementation(project(":domain:task-impl"))
    implementation(project(":feature:feature-chat"))
    implementation(project(":feature:feature-flows"))
    implementation(project(":feature:feature-memory"))
    implementation(project(":feature:feature-onboarding"))
    implementation(project(":feature:feature-overlay"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-tasks"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(project(":core:core-testing"))
}
