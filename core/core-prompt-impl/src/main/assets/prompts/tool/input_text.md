---
id: tool.input_text
version: 4
vars: []
meta:
  param.text: 要写入的文字
  param.x: 输入框横坐标，0–1000
  param.y: 输入框纵坐标，0–1000
  param.mode: replace 覆盖原有内容或 append 追加，默认 replace
  param.submit: 是否按回车发送（回车不可用时会提示点击发送按钮），默认否
  param.wait_ms: 输入后等待界面稳定的最长时间（毫秒）
---
在输入框中输入文字。给出输入框的坐标会先点击它；不给则输入到当前已激活的输入框。
找不到可输入的输入框且 shell 通道未连接时返回 device.not_editable，请先点击输入框或给出输入框坐标；
可写入但回车发送失败时会提示点击发送按钮。
