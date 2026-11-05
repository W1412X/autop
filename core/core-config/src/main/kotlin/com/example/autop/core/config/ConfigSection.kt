package com.example.autop.core.config

import kotlinx.serialization.KSerializer

/**
 * 一个配置段（配置树的一个子树），字段即配置键（docs/tech/02 §2）。
 *
 * - 实现类是不可变 `data class`，字段默认值只是缺字段时的兜底，
 *   与 assets/config/ 目录中对应 json 的默认值**必须**一致（由单元测试校验）。
 * - 持久化字段必须标注 `@SerialName`（硬性规则 4）。
 */
public interface ConfigSection {
    /** 自校验，返回问题描述（空列表 = 通过）。只做文档明确的约束检查。 */
    public fun validate(): List<String> = emptyList()
}

/**
 * 一个配置段的元信息：路径、序列化器、默认实例工厂（docs/tech/02 §2.1）。
 *
 * [path] 是配置树中的子树路径（如 `agent.compaction`，小写驼峰段）。
 * 同一子树对应唯一 ConfigKey；键的登记通过 DI 多绑定 `Set<ConfigKey<*>>`（02 §2.1 注）。
 */
public open class ConfigKey<T : ConfigSection>(
    public val path: String,
    public val serializer: KSerializer<T>,
    public val defaultInstance: () -> T,
)
