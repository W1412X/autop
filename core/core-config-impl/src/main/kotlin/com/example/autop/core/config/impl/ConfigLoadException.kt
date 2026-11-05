package com.example.autop.core.config.impl

/**
 * 启动期配置加载失败（默认值文件非法、schemaVersion 不符或键路径冲突），进程应终止。
 *
 * 继承 [IllegalStateException] 保留「启动失败」语义（测试断言兼容），同时允许携带 cause。
 */
public class ConfigLoadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
