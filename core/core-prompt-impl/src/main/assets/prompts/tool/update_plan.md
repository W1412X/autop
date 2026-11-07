---
id: tool.update_plan
version: 2
vars: []
meta:
  param.items: 计划项数组，每项 {step 步骤说明, status pending|doing|done|dropped}
  param.explanation: 本次更新的说明（会展示在计划卡上）
---
全量更新任务计划，随进展调用。
