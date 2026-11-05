# llm-openai

- 类型：IMPL（纯 Kotlin/JVM，llm 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.llm.openai`
- 职责（docs/tech/01 第 3 节，docs/tech/03）：OpenAI 兼容协议适配器（chat completions + embeddings），
  实现 `LlmProviderFactory`/`EmbeddingProviderFactory`（协议 id 见 docs/tech/03）。
- 依赖规则：可依赖 COMMON、API、同领域 CORE（llm-http）（docs/tech/01 第 4 节）。
- 类型归类说明见 docs/tech/questions.md N2。

## 组件（src/main/kotlin/…/llm/openai/）

- `OpenAiQuirks.kt`：`OpenAiQuirkKeys`（chatPath/embeddingsPath/maxTokensField/reasoningField/
  contextExceededPatterns/imageMaxEdge）+ `OpenAiQuirks.of(profile, defaults)` 解析与默认值。
- `OpenAiRequestMapper.kt`：统一模型 → chat completions 线格式（docs/tech/03 §5.1 请求表）——
  system 首条、单 Text 字符串 content、image_url data URL、tool_calls、tool 消息与含图结果的
  用户补充消息（模板 `llm.openai.tool_images_note`）、tools/tool_choice、maxTokensField quirk、
  stream_options；Thinking 不发送。
- `OpenAiStreamParser.kt`：chunk → 统一事件（TextDelta/ThinkingDelta/ToolCallStarted/
  ToolCallArgsDelta/UsageReported）；工具调用按 index 聚合，缺 id 生成 `call_<index>_<序号>`；
  `finish()` 组装 Completed（空 argsJson 改 `"{}"`）。
- `OpenAiProvider.kt`：`OpenAiContext`（运行时依赖打包）+ `OpenAiProvider`——MsgValidator 校验、
  RetryRunner 包 POST（Authorization: Bearer 经 SecretStore 取密钥）、`[DONE]` 结束、
  读段超时→`llm.timeout`/IO→`llm.network`（不重试）。
- `OpenAiProviderFactory.kt`：`PROTOCOL_ID = "openai"` 的 `LlmProviderFactory` 实现。
- `OpenAiEmbeddingProvider.kt`：`EMBEDDING_PROVIDER_ID = PROTOCOL_ID` 的 `EmbeddingProviderFactory` 实现
  （docs/tech/03 §7）——`POST {baseUrl}{embeddingsPath}`（quirk 可覆盖），响应 `data[i].embedding` 按
  `index` 排序映射，按 `batchSize` 切分顺序请求；维度未配置时 0=未知，首次 embed 后记录实际维度
  （questions.md N37）。
- `di/OpenAiModule.kt`：`OpenAiContext`/`OpenAiEmbeddingContext` 装配 + 两个工厂多绑定进扩展点集合。

## 测试（33 用例）

- `OpenAiRequestMapperTest`（7）：完整请求快照对比、单文本字符串 content、data URL 图片、
  含图工具结果补充消息、maxTokensField quirk、thinking 不发送、无 tools 省略。
- `OpenAiStreamParserTest`（6）：文本累积、参数分段拼接、缺 id 生成、usage 解析、finish_reason 映射、
  thinking 只展示不回传。
- `OpenAiQuirksTest`（3）：默认值、quirk 覆盖、空 patterns 回落。
- `OpenAiContractTest`（9）：docs/tech/03 §5.3 Provider 契约用例（`ProviderContractTest` 基类 +
  `src/test/resources/fixtures/` 夹具）：纯文本流、工具参数分段、多工具调用、工具结果含图请求体、
  usage 解析、429 重试、断流不重试、上下文超长映射、取消收集关连接。
- `OpenAiEmbeddingProviderTest`（8）：批量切分合并、data 按 index 排序、维度发现/配置维度、
  401→`llm.auth`、缺 profile→`llm.not_configured`、缺 key→`llm.auth`、请求路径与 Bearer 头。
