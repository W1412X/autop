# llm-anthropic

- 类型：IMPL（纯 Kotlin/JVM，llm 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.llm.anthropic`
- 职责（docs/tech/01 第 3 节，docs/tech/03）：Anthropic 兼容协议适配器（messages + SSE），
  实现 `LlmProviderFactory`（协议 id `anthropic`，见 docs/tech/03）。
- 依赖规则：可依赖 COMMON、API、同领域 CORE（llm-http）（docs/tech/01 第 4 节）。
- 类型归类说明见 docs/tech/questions.md N2。

## 组件（src/main/kotlin/…/llm/anthropic/）

- `AnthropicQuirks.kt`：`AnthropicQuirkKeys`（messagesPath/toolChoiceNone/contextExceededPatterns/
  imageMaxEdge）+ `ToolChoiceNoneMode`（NATIVE/OMIT_TOOLS）+ `AnthropicQuirks.of(profile, defaults)`
  解析与默认值（默认 `/v1/messages`、`omit_tools` → OMIT_TOOLS）。
- `AnthropicRequestMapper.kt`：统一模型 → messages 线格式（docs/tech/03 §5.2 请求表）——
  system 数组（cacheBreakpoint 加 `cache_control: ephemeral`）、相邻同角色合并、首条非 user 前插
  引导消息（模板 `llm.anthropic.leading_user`）、TOOL 消息 → user 消息里的 `tool_result` 块
  （`tool_use_id`/`is_error`）、Thinking 原样回传（`thinking`+`signature` / `redacted_thinking`）、
  图片 base64 `source`、tools → `input_schema`、tool_choice（auto/none/any；NONE+OMIT_TOOLS 不发 tools）、
  thinking budget 时不发 temperature；cacheBreakpointMessageIndex 指向消息的最后一个 content 块加缓存标记。
- `AnthropicStreamParser.kt`：SSE 事件 → 统一事件（message_start 记 input/cache 用量、content_block_start/delta
  聚合 Text/ToolCall/Thinking/Redacted、signature_delta 记签名、message_delta 记 stop_reason 与 output 用量、
  message_stop → UsageReported+Completed）；`finishIfNeeded()` 兜底（推断 TOOL_USE/END_TURN）；
  `error` 事件按 type 映射错误码抛 `LlmException`（overloaded→`llm.server`、rate_limit→`llm.rate_limited`、
  invalid_request→`llm.context_exceeded`/`llm.bad_request`、authentication→`llm.auth`、未知→`llm.bad_response`）。
- `AnthropicProvider.kt`：`AnthropicContext`（运行时依赖打包）+ `AnthropicProvider`——MsgValidator 校验、
  RetryRunner 包 POST（`x-api-key` 经 SecretStore 取密钥、`anthropic-version` 头）、
  读段超时→`llm.timeout`/IO→`llm.network`（不重试）。
- `AnthropicProviderFactory.kt`：`PROTOCOL_ID = "anthropic"` 的 `LlmProviderFactory` 实现。
- `di/AnthropicModule.kt`：`AnthropicContext` 装配 + 工厂多绑定进扩展点集合。

## 测试（25 用例）

- `AnthropicRequestMapperTest`（7）：完整请求快照对比（含 system/消息缓存标记、tool_use input、tool_choice any、
  thinking）、同角色合并与 leading user 前插、tool_result 进 user 消息、thinking/redacted 回传与非法参数
  空 input 兜底、tool_choice none 两种 quirk、thinking budget 取代 temperature、图片 base64 source。
- `AnthropicStreamParserTest`（6）：文本累积与 usage 聚合、工具参数分段拼接（空片段跳过）、
  thinking+signature+redacted 回传、finishIfNeeded 幂等与 stop_reason 推断、error 事件五型错误码映射、
  上下文模式 → `llm.context_exceeded`。
- `AnthropicQuirksTest`（3）：默认值、quirk 覆盖、空 patterns 回落。
- `AnthropicContractTest`（9）：docs/tech/03 §5.3 Provider 契约用例（`ProviderContractTest` 基类 +
  `src/test/resources/fixtures/` 夹具）：纯文本流、工具参数分段、多工具调用、工具结果含图请求体、
  usage 解析、429 重试、断流不重试、上下文超长映射、取消收集关连接。
