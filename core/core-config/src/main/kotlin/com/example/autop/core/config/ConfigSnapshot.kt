package com.example.autop.core.config

import kotlinx.serialization.json.JsonObject

/**
 * 合并后的不可变配置快照（docs/tech/02 §2.1）。
 *
 * 构造时对每个注册 key 解码并 `validate()`；失败的段回退 `defaultInstance()`，
 * 问题记入 [problems] 供设置页展示。线程安全：实例不可变，可跨线程共享。
 */
public interface ConfigSnapshot {
    /** 取某个配置段。段构造失败时返回该 key 的默认实例。 */
    public operator fun <T : ConfigSection> get(key: ConfigKey<T>): T

    /** 相对默认值的差异（与配置树同形的 JsonObject），写入 run_started 事件。 */
    public fun diffFromDefaults(): JsonObject

    /** 构造时发现的段级问题（“path: 原因”文本），空列表 = 全部健康。 */
    public fun problems(): List<String>
}
