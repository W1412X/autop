---
id: device.restart.stopped
version: 2
vars: [pkg, failed]
optionalVars: [failed]
---
已强制结束 {{pkg}}{{#if failed}}，重新打开失败；应用当前未运行，可以用 open_app 再试。{{/if}}
