---
id: agent.breaker.same_error_stop
version: 2
vars: [tool, count]
---
{{tool}} 连续 {{count}} 次失败。停止尝试，不要再调用任何工具，直接用纯文本向用户说明遇到的问题、已完成哪些步骤、用户接下来可以怎么做。
