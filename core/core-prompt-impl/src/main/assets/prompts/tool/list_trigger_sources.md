---
id: tool.list_trigger_sources
version: 1
vars: []
---
列出可用的事件源（条件触发的来源）：每个源的类型、说明、字段名、字段含义与示例值，以及过滤条件可用的操作符。
写条件任务的 filter 之前先调用它。filter 里的字段名必须来自这里的结果。kind 为 event 的源每条事件都会触发，为 state 的源只在状态变化时触发。
