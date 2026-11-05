/**
 * 快照导出/夹具格式（docs/tech/05 §11）：带 [SCHEMA_VERSION] 的 [ScreenSnapshot] 包装，
 * 调试页「导出当前快照 JSON」与测试夹具共用同一格式（规则④：payload 带 schemaVersion）。
 */
package com.example.autop.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 快照导出信封。 */
@Serializable
public data class SnapshotExport(
    /** 格式版本。 */
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    /** 屏幕快照本体。 */
    @SerialName("snapshot") val snapshot: ScreenSnapshot,
) {
    public companion object {
        /** 当前导出格式版本。 */
        public const val SCHEMA_VERSION: Int = 1
    }
}
