package com.example.autop.core.config

import com.example.autop.core.common.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 用户覆盖的写入端口（设置页与高级配置使用，docs/tech/02 §2.1）。
 *
 * 覆盖值持久化在文件型 DataStore（`config_overrides.json`，带 `schemaVersion`）。
 */
public interface ConfigEditor {
    /**
     * 在覆盖树 [path] 处写入 [value]。
     * 目标段解码或校验失败返回 `Err(config.invalid)`；路径不属任何已登记键返回
     * `Err(config.unknown_path)`。传入 `JsonNull` 表示清除该路径（恢复默认）。
     */
    public suspend fun setOverride(
        path: String,
        value: JsonElement,
    ): Outcome<Unit>

    /** 清除 [path] 处的覆盖（恢复默认）。 */
    public suspend fun clearOverride(path: String)

    /** 当前全部用户覆盖（与配置树同形，含 schemaVersion 之外的纯覆盖内容）。 */
    public suspend fun userOverrides(): JsonObject
}
