---
id: tool.wait
version: 2
vars: []
meta:
  param.ms: 固定等待时间（毫秒），与 until 二选一（同传时按 ms）
  param.until: 等待条件对象（至少给一个条件），{text_appears 文字出现, text_disappears 文字消失, package_is 切换到某应用}
  param.timeout_ms: 条件等待的超时时间（毫秒）
---
等待固定时间（ms），或等待某段文字出现/消失、应用切换（until，至少给一个条件）。页面加载需要时间时使用。
