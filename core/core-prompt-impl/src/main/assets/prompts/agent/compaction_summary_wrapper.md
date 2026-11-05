---
id: agent.compaction.summary_wrapper
version: 1
vars: [fromSeq, toSeq, summary, plan, trigger]
optionalVars: [plan, trigger]
---
[以下是第 {{fromSeq}}–{{toSeq}} 步的交接摘要，原始记录可以用 history_recall 查看]
{{summary}}
{{#if plan}}
当前计划：
{{plan}}{{/if}}{{#if trigger}}
触发事件：{{trigger}}{{/if}}
