---
id: device.scroll.result
version: 1
vars: [direction, steps, clampedTo, amountIgnored]
optionalVars: [clampedTo, amountIgnored]
---
已向{{direction}}滚动 {{steps}} 次{{#if clampedTo}}（steps 超出上限，已按 {{clampedTo}} 次执行）{{/if}}{{#if amountIgnored}}（amount 已废弃，已忽略）{{/if}}
