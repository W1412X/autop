---
id: tool.memory_search
version: 3
vars: []
meta:
  param.query: 要查的人名、应用名、关键词，或一句话问题；多个词用空格分隔效果更好
  param.path_prefix: 限定只搜某个目录前缀
  param.k: 最多返回条数
---
按关键词（条目的关键词、标题、路径名）与语义检索记忆，返回路径、来源标注（用户/agent/流程/导入）与摘要。只返回摘要，需要正文时用 memory_read。
