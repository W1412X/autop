# flow-api

流程领域的接口与模型：流程文档（步骤树）、运行入口、存储端口、校验/导入导出/AI 创作端口、错误码。纯 Kotlin，不依赖 Android。

对外接口：`FlowDefinition`、`FlowSession`/`FlowRunHandle`、`FlowStore`、`FlowRunStore`、`FlowStateStore`、`EndpointStore`、`FlowValidationService`、`FlowBundleService`、`FlowAuthor`。

扩展方式：新增节点/条件类型见 `docs/tech/11-flows.md` §6。
