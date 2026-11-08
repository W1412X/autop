---
id: tool.run_flow
version: 3
vars: []
meta:
  param.flow: 流程名称或 ID
  param.params: 流程参数（对象），按 list_flows 返回的参数声明填写
  param.start_at: 可选，从该节点 ID 开始执行
---
运行一个已有的自动化流程并等待结果。流程比你逐步操作更快更稳定，有现成流程时优先用它；先用 list_flows 查看有哪些流程和需要的参数。返回运行状态、摘要和流程的输出；失败时附出错的节点（failedNodeId、failedNodeName）与原因。单次运行受工具超时（默认 10 分钟）约束：返回体的 cappedByToolBudget=true 表示本次运行按工具预算收口，流程定义里的 maxDurationMs 更长也不会超出该预算。
