---
id: tool.run_flow
version: 1
vars: []
meta:
  param.flow: 流程名称或 ID
  param.params: 流程参数（对象），按 list_flows 返回的参数声明填写
  param.start_at: 可选，从该节点 ID 开始执行
---
运行一个已有的自动化流程并等待结果。流程比你逐步操作更快更稳定，有现成流程时优先用它；先用 list_flows 查看有哪些流程和需要的参数。返回运行状态、摘要和流程的输出。
