---
id: tool.ask_user
version: 2
vars: []
meta:
  param.question: 要问用户的问题
  param.options: 候选项列表（字符串数组），可省略
  param.timeout_s: 等待回答的超时时间（秒），缺省取工具预算（最长约 10 分钟），超时返回 agent.ask_timeout
---
向用户提问并等待回答。拿不准目标、缺少必要信息时使用。只是通知、不需要回答时用 notify_user。
