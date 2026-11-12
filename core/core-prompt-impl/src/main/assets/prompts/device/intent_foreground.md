---
id: device.intent.foreground
version: 1
vars: [pkg, unknown, unchanged]
optionalVars: [pkg, unknown, unchanged]
---
当前前台：{{#if pkg}}{{pkg}}{{/if}}{{#if unknown}}未获取到{{/if}}{{#if unchanged}}（与动作前相同：可能被系统拦截，或目标界面尚未出现。先观察，不要连续重发）{{/if}}
