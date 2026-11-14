package com.example.autop.core.platform

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler

/**
 * 用户数据 DataStore 的损坏处理（ADR-0032）。
 *
 * 数据结构拦截期间（[DataSchemaState.blocked]）直接抛出异常。ReplaceFileCorruptionHandler 在拿到新数据之后才会写回文件，
 * 抛出异常时文件保持原样，留给用户的备份或清除决定。其它情况返回 [fallback] 的默认值，与原来的行为相同。
 */
public fun <T> guardedReplaceHandler(fallback: () -> T): ReplaceFileCorruptionHandler<T> =
    ReplaceFileCorruptionHandler { ex ->
        if (DataSchemaState.blocked) throw ex
        fallback()
    }
