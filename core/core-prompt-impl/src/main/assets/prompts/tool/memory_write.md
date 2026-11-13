---
id: tool.memory_write
version: 4
vars: []
meta:
  param.path: 记忆条目路径
  param.mode: CREATE 新建、UPDATE 覆盖更新（用户内容自动降级为补充）、APPEND 追加
  param.content: 正文（详细内容，只在需要时被读取）
  param.title: 标题
  param.summary: 一句话摘要，不超过 60 字，用于列表和自动注入（新建时必填）
  param.keywords: 检索关键词，写用户以后可能提到的名字、别名、应用名等，每个不超过 20 字（新建时至少一个）
  param.reason: 写入原因；流程调用时缺省记 flow:<流程名>
  param.evidence: 证据对象 {package 来源应用, excerpt 原文片段}，记录信息是从哪里得知的，可省略
  param.force_new: 同目录下已有同名或关键词相同的条目时，确实是另一个对象才传 true；默认 false，遇到重复会被拒绝
---
写入或更新记忆。新建（CREATE）时必须给出 summary 和 keywords；同目录下已有同名或关键词相同的条目时会被拒绝，此时应改用 UPDATE 更新已有条目。
