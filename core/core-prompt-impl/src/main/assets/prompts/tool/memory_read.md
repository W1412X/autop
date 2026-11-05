---
id: tool.memory_read
version: 1
vars: []
meta:
  param.path: 记忆条目路径
  param.offset: 正文起始偏移（字符）
  param.limit: 本次最多返回字符数
---
读取记忆条目的正文，超出长度时分页返回。
