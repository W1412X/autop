---
id: task.author.warnings
version: 1
vars: [accessibility, shellSwitch, shellChannel, notificationListener]
---
{{#if accessibility}}- 无障碍服务未连接：需要屏幕的任务在无障碍连上前会一直等待，需要用户到系统设置开启无障碍。
{{/if}}{{#if shellSwitch}}- Shell 执行总闸未开启：完全模式任务开始时会直接失败，需要用户在设置里开启总闸。
{{/if}}{{#if shellChannel}}- shell 注入通道未连接：完全模式任务开始时会直接失败，需要用户开启无线调试并完成配对。
{{/if}}{{#if notificationListener}}- 通知使用权未授予：通知条件任务不会被触发，需要用户到系统设置授予通知使用权。
{{/if}}
