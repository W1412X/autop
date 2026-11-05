/**
 * 远端模型列表（N164）：拉取服务端可用模型 id，供设置页"获取模型列表"选择，
 * 避免手填显示名/别名导致服务端报 unsupported model name。
 */
package com.example.autop.llm

import com.example.autop.core.common.Outcome

/** 模型列表端口（llm-api 接口，llm-http 聚合、各协议适配器实现）。 */
public interface ModelDirectory {
    /** 拉取 [profile] 服务端可用的模型 id（保留服务端返回顺序）。 */
    public suspend fun listModels(profile: ModelProfile): Outcome<List<String>>
}

/** 模型列表协议扩展点（同 [LlmProviderFactory]：按 [protocolId] 匹配，禁止按 baseUrl 分支）。 */
public interface ModelDirectoryFactory {
    public val protocolId: String

    public fun create(): ModelDirectory
}
