---
id: device.observe.insufficient
version: 1
vars: [reason, suggest]
optionalVars: [suggest]
---
（信息不足：{{reason}}。{{#if suggest}}可以省略 mode 让工具自动选择，或改用 {{suggest}}。{{/if}}）
