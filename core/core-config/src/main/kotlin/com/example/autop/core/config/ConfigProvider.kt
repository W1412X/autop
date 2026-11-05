package com.example.autop.core.config

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 生效配置的读取端口（docs/tech/02 §2.1）。
 *
 * 生效配置 = assets 默认值 ⊕ 用户覆盖（⊕ Task/Run 级覆盖层经 [layered] 叠加）。
 */
public interface ConfigProvider {
    /** 当前全局生效配置（默认值 ⊕ 用户覆盖）。 */
    public fun current(): ConfigSnapshot

    /** 配置变化流（用户覆盖变更时发出新快照）。 */
    public fun observe(): StateFlow<ConfigSnapshot>

    /** 在全局配置上叠加若干覆盖层（Task 级、Run 级），返回新快照，不改变全局。 */
    public fun layered(vararg overlays: JsonObject): ConfigSnapshot
}
