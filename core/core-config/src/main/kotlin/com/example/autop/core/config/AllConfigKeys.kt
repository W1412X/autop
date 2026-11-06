package com.example.autop.core.config

/**
 * 全部配置键的汇总（docs/tech/02 §2.3 键路径不得冲突）。
 *
 * DI 层用 `@ElementsIntoSet` 把 [ALL] 提供为 `Set<ConfigKey<*>>`；
 * 一致性测试用它逐一比对 assets/config/ 目录中的默认值与 `defaultInstance`。
 */
public object AllConfigKeys {
    /** 全部已登记的配置键。 */
    public val ALL: List<ConfigKey<*>> =
        AgentConfig.ALL +
            LlmConfig.ALL +
            DeviceConfig.ALL +
            MemoryConfig.ALL +
            PolicyConfig.ALL +
            TaskConfig.ALL +
            UiConfig.ALL +
            FeaturesConfig.ALL +
            FlowConfig.ALL

    init {
        val duplicates = ALL.groupBy { it.path }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate config key paths: $duplicates" }
    }

    /** 按路径查找配置键；未知路径返回 null。 */
    public fun byPath(path: String): ConfigKey<*>? = ALL.firstOrNull { it.path == path }
}
