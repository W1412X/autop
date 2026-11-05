/**
 * OkHttp 客户端工厂（docs/tech/03 §4.1）：超时取自 `llm.http.*`；
 * 读超时对流式请求指“两个数据块之间的最大间隔”。
 */
package com.example.autop.llm.http

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** HTTP 超时配置（对应 `llm.http`）。 */
public data class HttpConfig(
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long,
)

/** 构造统一超时的 OkHttpClient。 */
public object HttpClientFactory {
    public fun create(config: HttpConfig): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
}
