---
id: tool.memory_write
version: 1
vars: []
meta:
  param.path: 记忆条目路径
  param.mode: CREATE 新建、UPDATE 覆盖更新（用户内容自动降级为补充）、APPEND 追加
  param.content: 正文
  param.title: 标题
  param.summary: 一句不超过 60 字的摘要
  param.tags: 标签列表
  param.reason: 写入原因
  param.evidence: 证据对象 {package 来源应用, excerpt 原文片段}，来自屏幕的信息必须填
---
写入或更新记忆。来自屏幕的信息必须填 evidence 并写明来源。
