package com.example.autop.policy.core

import com.example.autop.core.common.Logger

/** 空 Logger：测试只关心行为，不关心日志输出。 */
internal object NoopLogger : Logger {
    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit
}

/** 记录 warn 事件名的 Logger：断言“未知类型被跳过”等告警。 */
internal class RecordingLogger : Logger {
    val warnEvents: MutableList<String> = mutableListOf()

    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) {
        warnEvents += event
    }

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit
}
