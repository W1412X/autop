package com.example.autop.buildlogic.internal

/**
 * 模块类型（docs/tech/01 第 4 节）。类型由 Gradle 路径推断，决定允许的依赖矩阵。
 */
enum class ModuleType { COMMON, API, CORE, IMPL, FEATURE, APP }

/** 参与依赖规则校验的模块信息。 */
data class ModuleInfo(
    /** Gradle 路径，如 `:core:core-common`。 */
    val path: String,
    /** 模块名（路径最后一段），如 `core-common`。 */
    val name: String,
    val type: ModuleType,
    /** 领域名，用于“仅同领域”判断（如 `device`）。 */
    val domain: String,
)

object ModuleTypes {
    /** core-testing 只能出现在 testImplementation，其余配置一律视为违规。 */
    const val CORE_TESTING_PATH: String = ":core:core-testing"

    private val jvmTypes = setOf(ModuleType.COMMON, ModuleType.API, ModuleType.CORE)

    /** IMPL 类型模块允许被 IMPL 依赖的白名单（docs/tech/01 第 4 节矩阵）。 */
    private val implToImplWhitelist =
        setOf(":core:core-platform", ":core:core-ui", ":core:core-config-impl", ":core:core-prompt-impl")

    /** 按 docs/tech/01 第 4 节推断表推断类型。 */
    fun infer(path: String): ModuleInfo {
        val name = path.substringAfterLast(':')
        val type = when {
            path == ":core:core-common" -> ModuleType.COMMON
            path == ":app" -> ModuleType.APP
            path.startsWith(":feature:") -> ModuleType.FEATURE
            name.endsWith("-api") || path == ":core:core-config" || path == ":core:core-prompt" -> ModuleType.API
            name.endsWith("-core") || path == ":agent:agent-runtime" || path == ":core:core-testing" -> ModuleType.CORE
            name.endsWith("-impl") || path == ":agent:agent-store" ||
                path == ":core:core-platform" || path == ":core:core-ui" -> ModuleType.IMPL
            // 文档缺口（见 questions.md N2）：llm-http 是适配器共享的传输层，视为 llm 领域 CORE；
            // llm-openai / llm-anthropic 是 llm-api 端口的适配器实现，视为 llm 领域 IMPL。
            path == ":llm:llm-http" -> ModuleType.CORE
            path == ":llm:llm-openai" || path == ":llm:llm-anthropic" || path == ":llm:llm-onnx" -> ModuleType.IMPL
            else -> error("无法按 docs/tech/01 第 4 节推断模块类型：$path")
        }
        return ModuleInfo(path, name, type, domainOf(path, name, type))
    }

    /**
     * 领域名：模块名去掉 `-api/-core/-impl` 后缀；agent-runtime/agent-store 同属 agent 领域；
     * llm-* 同属 llm 领域；feature/app 各自独立。
     */
    private fun domainOf(path: String, name: String, type: ModuleType): String = when {
        path == ":app" -> "app"
        type == ModuleType.FEATURE -> name
        path == ":agent:agent-runtime" || path == ":agent:agent-store" -> "agent"
        path.startsWith(":llm:") -> "llm"
        else -> name.removeSuffix("-api").removeSuffix("-core").removeSuffix("-impl")
    }

    fun isJvmOnlyType(type: ModuleType): Boolean = type in jvmTypes

    /** 允许矩阵（行依赖列，docs/tech/01 第 4 节）。 */
    fun isAllowed(from: ModuleInfo, to: ModuleInfo): Boolean = when (from.type) {
        ModuleType.COMMON -> false
        ModuleType.API -> to.type == ModuleType.COMMON || to.type == ModuleType.API
        ModuleType.CORE -> when (to.type) {
            ModuleType.COMMON, ModuleType.API -> true
            ModuleType.CORE -> to.domain == from.domain
            else -> false
        }
        ModuleType.IMPL -> when (to.type) {
            ModuleType.COMMON, ModuleType.API -> true
            ModuleType.CORE -> to.domain == from.domain
            ModuleType.IMPL -> to.path in implToImplWhitelist
            else -> false
        }
        ModuleType.FEATURE -> when (to.type) {
            ModuleType.COMMON, ModuleType.API -> true
            ModuleType.IMPL -> to.path == ":core:core-ui"
            else -> false
        }
        ModuleType.APP -> true
    }
}
