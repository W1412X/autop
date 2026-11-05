package com.example.autop.core.config

/** 配置错误码（docs/tech/02 §2.4）。 */
public object ConfigErrorCodes {
    /** 设置覆盖值后目标段解码失败或校验不通过。 */
    public const val INVALID: String = "config.invalid"

    /** 覆盖路径不属于任何已登记的配置键。 */
    public const val UNKNOWN_PATH: String = "config.unknown_path"

    /** 用户覆盖文件的 schemaVersion 迁移失败（链缺失或迁移函数抛错）。 */
    public const val MIGRATION_FAILED: String = "config.migration_failed"
}
