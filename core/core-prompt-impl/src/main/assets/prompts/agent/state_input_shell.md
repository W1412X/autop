---
id: agent.state.input.shell
version: 1
vars: [connected, connecting, offPaired, offUnpaired]
optionalVars: [connected, connecting, offPaired, offUnpaired]
---
注入通道：{{#if connected}}shell 已连接，点击、滑动、返回键走 shell，等同真实触摸{{/if}}{{#if connecting}}shell 正在连接，暂时用无障碍注入{{/if}}{{#if offPaired}}shell 未连接（已配对过），点击和滑动改用无障碍注入，部分应用会忽略这种手势；可请用户在 AutoP 设置里重新连接无线调试{{/if}}{{#if offUnpaired}}shell 未配对，点击和滑动用无障碍注入，部分应用会忽略这种手势；可请用户在 AutoP 设置里配对无线调试{{/if}}
