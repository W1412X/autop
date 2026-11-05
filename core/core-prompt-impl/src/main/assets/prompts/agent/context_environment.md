---
id: agent.context.environment
version: 1
vars: [localTime, weekday, timezone, battery, charging, network, foregroundApp, locked]
---
当前环境：{{localTime}}（{{weekday}}，{{timezone}}）；电量 {{battery}}%{{#if charging}}，充电中{{/if}}；网络 {{network}}；前台应用：{{foregroundApp}}{{#if locked}}；屏幕已锁定{{/if}}。
