# llm-http

- 类型：CORE（llm 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.llm.http`
- 职责（docs/tech/03 §4.1–§4.4）：适配器共享的 HTTP 传输层——OkHttp 客户端工厂、挂起执行、
  SSE 流解析、重试策略、HTTP 失败到统一错误码的映射。OpenAI/Anthropic 适配器只做线格式转换，
  传输细节全部复用这里。
- 依赖规则：`api(project(":llm:llm-api"))` + `api(libs.okhttp)` + `implementation(project(":core:core-config"))`；
  di/ 的 `@InstallIn` 来自 `compileOnly(libs.hilt.core)`（运行时由 app 提供）；测试用 mockwebserver3 与 core-testing。

## 组件（src/main/kotlin/…/llm/http/）

- `HttpClientFactory.kt`：`HttpConfig`（connect/read/write 超时）+ `HttpClientFactory.create()`。
- `CallAwait.kt`：`Call.await()`——`enqueue` + `suspendCancellableCoroutine`，协程取消即 `Call.cancel()`。
- `SseEvent.kt`：`SseEvent(event, data)` + `BufferedSource.sseEvents()`——空行分隔事件、多行 data 用
  `\n` 连接、`:` 注释忽略、`event:`/`data:` 去前导一个空格、未知字段忽略、流结束补发
  （docs/tech/03 §4.2 规则 1–7）。阻塞读取，调用方 `flowOn` 指定线程。
- `Retry.kt`：`RetryConfig`（maxAttempts/baseDelayMs/maxDelayMs/retryStatus）+ `AttemptOutcome` +
  `RetryRunner`——可重试失败按指数退避（×2）+ 0.8~1.2 抖动，`Retry-After` 优先并封顶 maxDelay；
  SocketTimeout→`llm.timeout`、IOException→`llm.network`（均 retryable）；取消自然传播。
  流式已发出增量后的失败由调用方以 `retryable=false` 表达，不再重试。
- `HttpErrorMapper.kt`：`mapHttpError`（400+contextExceededPatterns→`llm.context_exceeded`、
  401/403→`llm.auth`、429→`llm.rate_limited`、5xx→`llm.server`、其余 4xx→`llm.bad_request`、
  其他→`llm.bad_response`）+ `matchesContextExceeded`（大小写不敏感）+ `parseRetryAfterMs`
  （秒数或 HTTP 日期，非法返回 null）。
- `DefaultLlmGateway.kt`：`LlmGateway` 默认实现（docs/tech/10 WP3）——多绑定注入 `LlmProviderFactory`，
  按 `profile.protocol` 匹配并缓存 provider，`ModelProfileStore` 变化即失效重建；非 suspend 查询首次
  同步加载快照保证确定性（questions.md N34）。
- `DefaultModelProbe.kt`：`ModelProbe` 实现——依次发 text/tools/vision 三个最小请求（烟测文本是协议
  载荷常量、不进 prompt 模板，questions.md N35），单项超时 `llm.probe.timeoutMs`→`llm.timeout`，
  vision 用内置 8×8 PNG。
- `ProbePng.kt`：手写 PNG 编码器（无 AWT/Bitmap 依赖），`solidRgb8x8()` 供探测图。
- `DefaultEmbeddingGateway.kt`：`EmbeddingGateway` 实现（docs/tech/03 §7）——按 `llm.embedding` 选
  `EmbeddingProviderFactory`，`none`/未知→null，配置变化发新实例（`modelId` 变化供重建索引）。
- `di/`：`LlmHttpModule`（OkHttpClient/RetryConfig 从配置组装、三端口绑定）+ `ProbeTempDir` 限定符
  （内置图写入目录，app 侧绑定 cacheDir，questions.md N36）。

## 测试（41 用例）

- `SseTest`（6）：单事件、多行 data、注释/未知字段、event 名与单空格剥离、CRLF 与末尾补发、跨 chunk 解析。
- `RetryTest`（8）：成功不等待、指数退避序列、封顶、Retry-After 优先与封顶、不可重试即停、
  次数耗尽返回最后错误、IOException 重试、SocketTimeout→`llm.timeout`。
- `HttpErrorMapperTest`（10）：状态码映射全表、context 匹配大小写、Retry-After 秒/日期/非法解析。
- `DefaultLlmGatewayTest`（6）：角色未分配/未知 id/未知协议→`llm.not_configured`、角色映射、
  provider 缓存复用、配置变化重建。
- `DefaultModelProbeTest`（5）：三项全 ok、无 factory 三项失败、错误透传、悬挂→`llm.timeout`、
  vision 请求带本地 8×8 PNG。
- `DefaultEmbeddingGatewayTest`（4）：默认 `none`→null、按配置创建、未知 provider→null、变化发新实例。
- `ProbePngTest`（2）：PNG 签名、ImageIO 解码 8×8。
