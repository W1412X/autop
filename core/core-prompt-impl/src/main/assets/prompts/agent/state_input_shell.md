---
id: agent.state.input.shell
version: 2
vars: [connected, connecting, offPaired, offUnpaired]
optionalVars: [connected, connecting, offPaired, offUnpaired]
---
坐标操作（tap、swipe、scroll、global_action）当前模式：{{#if connected}}shell 注入（等同真实触摸）{{/if}}{{#if connecting}}无障碍注入（shell 正在连接，连上后自动改用 shell）{{/if}}{{#if offPaired}}无障碍注入（shell 未连接，已配对过；部分应用会忽略无障碍注入的手势，可请用户在 AutoP 设置里重新连接无线调试）{{/if}}{{#if offUnpaired}}无障碍注入（shell 未配对；部分应用会忽略无障碍注入的手势，可请用户在 AutoP 设置里配对无线调试）{{/if}}。节点操作（tap_node、scroll_node）不走注入，直接对节点执行无障碍动作。
