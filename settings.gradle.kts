pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "autop"

// 模块清单见 docs/tech/01 第 3 节
include(":core:core-common")
include(":core:core-config")
include(":core:core-config-impl")
include(":core:core-prompt")
include(":core:core-prompt-impl")
include(":core:core-platform")
include(":core:core-ui")
include(":core:core-testing")

include(":llm:llm-api")
include(":llm:llm-http")
include(":llm:llm-openai")
include(":llm:llm-anthropic")
include(":llm:llm-onnx")

include(":agent:agent-api")
include(":agent:agent-runtime")
include(":agent:agent-store")

include(":domain:device-api")
include(":domain:device-core")
include(":domain:device-impl")
include(":domain:memory-api")
include(":domain:flow-api")
include(":domain:flow-core")
include(":domain:flow-impl")
include(":domain:memory-impl")
include(":domain:policy-api")
include(":domain:policy-core")
include(":domain:task-api")
include(":domain:task-core")
include(":domain:task-impl")

include(":feature:feature-chat")
include(":feature:feature-tasks")
include(":feature:feature-flows")
include(":feature:feature-memory")
include(":feature:feature-settings")
include(":feature:feature-onboarding")
include(":feature:feature-overlay")

include(":app")
