@file:Suppress("LongParameterList", "NestedBlockDepth", "ComplexCondition") // 接口调用：重定向循环与路径校验

package com.example.autop.flow.impl.ports

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.SecretStore
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.flow.ApiEndpoint
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.ApiCall
import com.example.autop.flow.core.ApiPort
import com.example.autop.flow.core.ApiReply
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Values
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * API 端口（docs/design/flows.md §11.4）：只访问已登记接口，请求只发往登记的主机；
 * 重定向仅允许同主机且最多 [MAX_REDIRECTS] 次；`path` 不得含绝对地址、`//`、`@`、`..`。
 */
internal class ApiPortImpl(
    private val endpoints: EndpointStore,
    private val secrets: SecretStore,
    private val configProvider: ConfigProvider,
    private val dispatchers: DispatcherProvider,
    private val client: OkHttpClient,
) : ApiPort {
    private val json = AutopJson.default

    override suspend fun call(call: ApiCall): ApiReply {
        val cfg = configProvider.current()[FlowConfig.API]
        val endpoint =
            endpoints.get(call.endpoint)?.takeIf { it.enabled }
                ?: throw FlowException(FlowErrorCodes.ENDPOINT_UNREGISTERED, "接口未登记或已停用：${call.endpoint}")
        val base =
            endpoint.baseUrl.toHttpUrlOrNull()
                ?: throw FlowException(FlowErrorCodes.ENDPOINT_UNREGISTERED, "接口地址无效：${endpoint.baseUrl}")
        if (!base.isHttps && !cfg.allowHttp) {
            throw FlowException(FlowErrorCodes.ENDPOINT_UNREGISTERED, "只允许 https 接口：${endpoint.name}")
        }
        val url = buildUrl(base, call)
        val body = buildBody(call, cfg.maxRequestBytes)
        val timeout = call.timeoutMs ?: cfg.timeoutMs
        val http =
            client
                .newBuilder()
                .followRedirects(false)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
        return withContext(dispatchers.io) { execute(http, endpoint, base, url, call, body, cfg.maxResponseBytes) }
    }

    private fun buildUrl(
        base: HttpUrl,
        call: ApiCall,
    ): HttpUrl {
        val path = call.path
        if (path.contains("//") || path.contains("@") || path.contains("..") || path.contains("://")) {
            throw FlowException(FlowErrorCodes.ARGS_INVALID, "接口 path 不合法：$path")
        }
        val builder = base.newBuilder()
        path
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { builder.addPathSegment(it) }
        call.query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun buildBody(
        call: ApiCall,
        maxBytes: Int,
    ): RequestBody? {
        if (call.method == "GET") return null
        val body = call.body
        val built =
            when (call.bodyType) {
                "json" -> (body ?: JsonNull).toString().toRequestBody(JSON_TYPE.toMediaType())
                "text" -> Values.str(body).toRequestBody(TEXT_TYPE.toMediaType())
                "form" -> {
                    val form = FormBody.Builder()
                    (body as? JsonObject)?.forEach { (k, v) -> form.add(k, Values.str(v)) }
                    form.build()
                }
                else -> null
            }
        if (built != null && built.contentLength() > maxBytes) {
            throw FlowException(FlowErrorCodes.ARGS_INVALID, "请求体超过上限 $maxBytes 字节")
        }
        return built ?: if (call.method == "GET") null else ByteArray(0).toRequestBody(null)
    }

    private suspend fun execute(
        http: OkHttpClient,
        endpoint: ApiEndpoint,
        base: HttpUrl,
        url: HttpUrl,
        call: ApiCall,
        body: RequestBody?,
        maxResponse: Int,
    ): ApiReply {
        var current = url
        var redirects = 0
        while (true) {
            val request = request(endpoint, current, call, body)
            val response = http.newCall(request).await()
            response.use { r ->
                if (r.isRedirect && redirects < MAX_REDIRECTS) {
                    val next = r.header("Location")?.let { current.resolve(it) }
                    if (next != null && next.host == base.host && next.scheme == base.scheme) {
                        current = next
                        redirects++
                        return@use
                    }
                    throw FlowException(FlowErrorCodes.API_FAILED, "接口重定向到了未登记的地址")
                }
                return reply(r, maxResponse)
            }
        }
    }

    private suspend fun request(
        endpoint: ApiEndpoint,
        url: HttpUrl,
        call: ApiCall,
        body: RequestBody?,
    ): Request {
        val builder = Request.Builder().url(url).method(call.method, body)
        endpoint.headers.forEach { (k, v) -> builder.header(k, v) }
        call.headers.forEach { (k, v) -> builder.header(k, v) }
        endpoint.secretRef?.let { ref ->
            val secret =
                secrets.get(ref) ?: throw FlowException(FlowErrorCodes.ENDPOINT_UNREGISTERED, "接口密钥缺失：${endpoint.name}")
            builder.header(endpoint.secretHeader, endpoint.secretPrefix + secret)
        }
        return builder.build()
    }

    private fun reply(
        r: Response,
        maxResponse: Int,
    ): ApiReply {
        val text = r.peekBody(maxResponse.toLong()).string()
        val parsed: JsonElement? =
            try {
                json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        val headers = r.headers.names().associateWith { r.header(it).orEmpty() }
        return ApiReply(r.code, headers, parsed, text)
    }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        cont.resumeWithException(FlowException(FlowErrorCodes.API_FAILED, e.message ?: "网络错误"))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        cont.resume(response)
                    }
                },
            )
        }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val JSON_TYPE = "application/json; charset=utf-8"
        const val TEXT_TYPE = "text/plain; charset=utf-8"
    }
}
