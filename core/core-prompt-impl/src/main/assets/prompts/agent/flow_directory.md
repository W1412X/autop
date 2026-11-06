---
id: agent.flow_directory
version: 1
vars: [items, omitted]
---
可用流程（开始任务前先看有没有匹配的流程；匹配时用 run_flow 调用并通过 params 传参数；流程失败时自己接手完成任务）：
{{items}}
{{#if omitted}}另有 {{omitted}} 个流程未列出，可用 list_flows 查看。{{/if}}
