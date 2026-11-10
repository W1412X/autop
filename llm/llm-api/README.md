# llm-api

- 类型：API ｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.llm`
- 职责（docs/tech/03 第 1–2 节）：LLM 统一抽象层——统一消息模型、请求/流式事件、Provider 与网关端口、
  模型档案与探针、Embedding 端口、错误码。任何模块只依赖本模块，不接触具体厂商线格式。
- 依赖规则：API 模块，可被任意模块依赖；实现方为 `:llm:llm-openai`、`:llm:llm-anthropic`（IMPL）与
  `:llm:llm-http`（CORE 传输层）。本模块只 `api(project(":core:core-common"))`。

## 消息模型（src/main/kotlin/…/llm/）

- `Message.kt`：`Role`（USER/ASSISTANT/TOOL）与 `Block` 密封层级
  （`Text`/`Image`/`ToolCall`/`ToolResult`/`Thinking`）+ `Msg`（`producedBy`：产生该消息的模型配置 id，
  历史净化用）；字段 `@SerialName` 与事件 payload 带 `v`。
- `MsgValidator.kt`：`MsgValidator.problems(messages)` 收集结构约束违反项（ToolCall 只能在 assistant、
  ToolResult 只能在 tool、tool 消息只含 ToolResult 且 callId 集合与未决调用一一对应、user 消息前不允许有
  未决调用、结尾不允许未决调用），**不抛异常**；Provider 发送前检查，非空抛
  `LlmException(AppError(llm.invalid_history))`。`validate` 保留为断言式入口。

## 请求与流事件

- `LlmRequest.kt`：`SystemPart`（含 `cacheBreakpoint`）、`ToolSchema`/`ToolChoice`、`LlmRequest`
  （system/messages/tools/toolChoice/maxOutputTokens/temperature/thinkingBudgetTokens/cacheBreakpointMessageIndex）。
- `LlmStreamEvent.kt`：`Usage`（`inputTokens` = 本次请求全部输入 token，含缓存命中与缓存写入）、
  `StopReason` 与密封事件
  `TextDelta`/`ThinkingDelta`/`ToolCallStarted`/`ToolCallArgsDelta`/`UsageReported`/`Completed`。

## 端口

- `LlmProvider.kt`：`ModelRole`（MAIN/VISION/LIGHT）；`LlmProvider`（`profile` + `stream(request)`）；
  `LlmProviderFactory`（`protocolId` 匹配扩展点，禁止按 baseUrl 分支）；`LlmGateway`
  （`forRole`/`forProfile`）；`LlmException`（携带 `AppError`）；
  `LlmProvider.complete(request)` 收敛流为 `Outcome<Completed>`（取消原样传播）。
- `ModelProfile.kt`：`ModelCapabilities` + `ModelProfile`（protocol/baseUrl/apiKeyRef/model/
  contextWindow/maxOutputTokens/quirks/extraHeaders）。
- `ModelProfileStore.kt`：`ModelRoles` + 档案仓库端口（`profiles`/`roles` Flow、`get`/`upsert`/`delete`/`setRoles`）。
- `ModelProbe.kt`：`ProbeResult`/`ProbeReport`（text/tools/vision 三项探测）+ `ModelProbe` 端口。
- `Embedding.kt`：`EmbeddingProvider`/`EmbeddingProviderFactory`/`EmbeddingGateway` + `EmbeddingConfig`。
- `ImageLoader.kt`：`ImageLoader.load(fileRef, mime, maxEdge)` 图片读取端口（实现方负责长边限制）
  + `LoadedImage`；消息模型只存文件引用，base64 由适配器完成。

## 错误码

- `LlmErrorCodes.kt`：`llm.not_configured`/`auth`/`rate_limited`/`context_exceeded`/`bad_request`/
  `server`/`network`/`timeout`/`bad_response`/`refused`/`invalid_history`（docs/tech/03 §4.4 照抄）。
  `retryable` 由 `mapHttpError` 单一判定（HTTP 状态码 ∈ `retryStatus`；`context_exceeded` 恒 false）。

## 测试

- `MsgValidatorTest`（9 用例）：合法对话、工具调用配对、跨角色块、未决调用、重复 id 等约束。
