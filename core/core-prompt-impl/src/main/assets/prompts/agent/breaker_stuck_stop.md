---
id: agent.breaker.stuck_stop
version: 2
vars: [count]
---
连续 {{count}} 个操作都没有让屏幕发生变化。停止操作，不要再调用任何工具，直接用纯文本向用户说明：已完成哪些步骤、卡在了哪里、用户接下来可以怎么做。
