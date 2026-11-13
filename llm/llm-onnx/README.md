# llm-onnx

- 类型：IMPL（Android 库，llm 领域）｜ 插件：`autop.android.library` + `autop.hilt` ｜ 包名：`com.example.autop.llm.onnx`
- 职责：端侧 embedding 提供方（ADR-0028）。bge-small-zh-v1.5 int8，ONNX Runtime CPU 推理，512 维、已归一化。
  注册为 `llm.embedding.provider = local`。

## 组件（src/main/kotlin/…/llm/onnx/）

- `WordPieceTokenizer.kt`：BERT 分词，与 HuggingFace tokenizers（`BertNormalizer` 不小写、`BertPreTokenizer`、WordPiece）一致；
  `padBatch` 把变长编码对齐成矩形 batch。
- `LocalModelStore.kt`：读取 `manifest.json`，逐个校验清单中的文件（大小与 SHA-256），解析词表。错误码
  `llm.embedding_model_missing` / `llm.embedding_model_corrupt`。
- `LocalEmbeddingProvider.kt`：清单同步读取（modelId、维度）；模型与会话在第一次 `embed` 时惰性装载并缓存（失败也缓存）；
  推理串行，CPU 工作在 `dispatchers.default`。输出 `sentence_embedding`。
- `LocalEmbeddingProviderFactory.kt`：工厂，`id = "local"`，从 AssetManager 读取资产。
- `di/LocalEmbeddingModule.kt`：经 `@IntoSet` 注册到 `EmbeddingProviderFactory` 集合。

## 资产

`src/main/assets/embedding/bge-small-zh-v1.5-int8/`：模型（单文件，外部权重已合并）、词表、配置、清单、上游说明。
来源与许可证见 `manifest.json` 与 `README-upstream.md`（MIT）。更换模型时同步更新清单；`version` 加一会触发向量全量重建。

## 测试

- `WordPieceTokenizerTest`：分词与 batch 对齐的小词表用例。
- `TokenizerParityTest`：与 HuggingFace 官方输出逐条对照（`src/test/resources/tokenizer-parity.json`）。
- `ModelIntegrityTest`：随包资产的清单校验，篡改与缺失时的错误码。

ONNX 推理需要真机验证（ONNX Runtime 的 Android 原生库无法在 JVM 上运行）。
