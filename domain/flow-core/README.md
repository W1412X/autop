# flow-core

流程的纯算法：表达式与模板、解释器、条件求值、目标匹配、观测 JSON 解析、LLM 输出校验、流程校验与 needsScreen 分析。

对外接口：`FlowInterpreter`（端口见 `Ports.kt`）、`FlowValidator`、`FlowAnalyzer`。

测试：JVM 单测，用假端口驱动解释器。
