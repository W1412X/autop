/**
 * Provider 契约测试的通用支撑（docs/tech/03 §5.3）：本地 SSE 服务器、请求构造与事件收集。
 * 只服务 [ProviderContractTest] 及其子类。
 */
package com.example.autop.core.testing

import com.example.autop.llm.Block
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.BufferedReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 启动本地 MockWebServer 并在 [block] 结束后关闭。 */
internal fun withMockServer(block: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    try {
        server.start()
        block(server)
    } finally {
        server.close()
    }
}

/** 一条 200 + `text/event-stream` 的 SSE 响应（正文由夹具提供）。 */
internal fun sseResponse(body: String): MockResponse =
    MockResponse
        .Builder()
        .code(HTTP_OK)
        .addHeader("content-type", "text/event-stream")
        .body(body)
        .build()

/** 收集完整事件流；流抛出的异常直接向上传播（用例 1–6 的成功路径）。 */
internal fun collectEvents(
    provider: LlmProvider,
    request: LlmRequest,
): List<LlmStreamEvent> =
    runBlocking {
        withTimeout(COLLECT_TIMEOUT_MS) {
            provider.stream(request).toList()
        }
    }

/** 收集事件流并捕获 [LlmException]（用例 7/8 的失败路径），取消不吞、超时抛出。 */
internal fun collectEventsResult(
    provider: LlmProvider,
    request: LlmRequest,
): CollectResult {
    val events = Collections.synchronizedList(mutableListOf<LlmStreamEvent>())
    var error: LlmException? = null
    runBlocking {
        try {
            withTimeout(COLLECT_TIMEOUT_MS) {
                provider.stream(request).collect { events += it }
            }
        } catch (e: LlmException) {
            error = e
        }
    }
    return CollectResult(events.toList(), error)
}

/** 用例 1/2/3/5/6/7/9 的最小纯文本请求。 */
internal fun contractTextRequest(): LlmRequest =
    LlmRequest(
        system = listOf(SystemPart("contract system")),
        messages = listOf(Msg(Role.USER, listOf(Block.Text("hi")))),
        maxOutputTokens = MAX_OUTPUT_TOKENS,
    )

/** 用例 4 的请求：USER → ASSISTANT 工具调用 → TOOL 工具结果（结果含图片）。 */
internal fun contractToolRequest(tools: List<ContractTool>): LlmRequest =
    LlmRequest(
        system = listOf(SystemPart("contract system")),
        messages =
            listOf(
                Msg(Role.USER, listOf(Block.Text("hi"))),
                Msg(Role.ASSISTANT, tools.map { Block.ToolCall(it.id, it.name, it.argsJson) }),
                Msg(
                    Role.TOOL,
                    tools.map {
                        Block.ToolResult(
                            callId = it.id,
                            content = listOf(Block.Image(IMAGE_REF, IMAGE_MIME, IMAGE_WIDTH, IMAGE_HEIGHT)),
                            isError = false,
                        )
                    },
                ),
            ),
        tools = tools.map { ToolSchema(it.name, "contract tool", JsonObject(emptyMap())) },
        toolChoice = ToolChoice.AUTO,
        maxOutputTokens = MAX_OUTPUT_TOKENS,
    )

/** 读完一个 HTTP/1.1 请求（请求行 + 头部 + Content-Length 长度的正文），返回正文。 */
internal fun readHttpRequest(reader: BufferedReader): String {
    var contentLength = 0
    while (true) {
        val line = reader.readLine() ?: return ""
        if (line.isEmpty()) break
        if (line.startsWith(HEADER_CONTENT_LENGTH, ignoreCase = true)) {
            contentLength = line.substringAfter(":").trim().toInt()
        }
    }
    val body = CharArray(contentLength)
    var read = 0
    while (read < contentLength) {
        val count = reader.read(body, read, contentLength - read)
        if (count < 0) break
        read += count
    }
    return String(body, 0, read)
}

/**
 * 用例 7 的截断服务器：虚报 [TRUNCATE_OVERHEAD_BYTES] 字节 Content-Length，
 * 写完 [payload] 即断开连接；每个进入的连接计入 [connections]。
 * 先读完请求再回写：若带着未读的请求数据关 socket，栈会发 RST 把已写给客户端的
 * 响应一并作废（负载下时序漂移即丢掉全部增量、只剩 NETWORK 错误），与截断语义无关。
 */
internal fun startTruncatedServer(
    payload: String,
    connections: AtomicInteger,
): ServerSocket {
    val body = payload.toByteArray(StandardCharsets.UTF_8)
    val server = ServerSocket(0)
    serveOnce(server, connections) { socket ->
        val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
        readHttpRequest(input)
        val head =
            "$STATUS_LINE_OK\r\n" +
                "content-type: text/event-stream\r\n" +
                "Content-Length: ${body.size + TRUNCATE_OVERHEAD_BYTES}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
        socket.close()
    }
    return server
}

/**
 * 用例 9 的挂起服务器：写完 [payload] 后保持连接，阻塞读到对端关闭即
 * 唤醒 [clientClosed]（取消收集后 Provider 经 awaitClose 关连接）。
 */
internal fun startHangServer(
    payload: String,
    clientClosed: CountDownLatch,
): ServerSocket {
    val body = payload.toByteArray(StandardCharsets.UTF_8)
    val server = ServerSocket(0)
    serveOnce(server, AtomicInteger()) { socket ->
        val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
        readHttpRequest(input)
        val head =
            "$STATUS_LINE_OK\r\n" +
                "content-type: text/event-stream\r\n" +
                "Content-Length: $HANG_CONTENT_LENGTH\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
        runCatching { input.read() }
        clientClosed.countDown()
        socket.close()
    }
    return server
}

/** 在守护线程循环接受连接并交给 [onSocket]，直到 [server] 关闭；每个连接计入 [connections]。 */
private fun serveOnce(
    server: ServerSocket,
    connections: AtomicInteger,
    onSocket: (Socket) -> Unit,
) {
    thread(name = "contract-server", isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            connections.incrementAndGet()
            runCatching { onSocket(socket) }
        }
    }
}

private const val COLLECT_TIMEOUT_MS = 30_000L
private const val MAX_OUTPUT_TOKENS = 128
private const val IMAGE_REF = "shot.png"
private const val IMAGE_MIME = "image/png"
private const val IMAGE_WIDTH = 100
private const val IMAGE_HEIGHT = 80
private const val TRUNCATE_OVERHEAD_BYTES = 500
private const val HANG_CONTENT_LENGTH = 1_000_000
private const val HTTP_OK = 200
private const val HEADER_CONTENT_LENGTH = "Content-Length:"
private const val STATUS_LINE_OK = "HTTP/1.1 200 OK"
