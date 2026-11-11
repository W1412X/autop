---
id: tool.tap_node
version: 1
vars: []
meta:
  param.id: a11y 观测里节点的 id 字段
  param.long: 是否长按，默认否
  param.wait_ms: 动作后等待界面稳定的最长时间（毫秒），一般不用填
---
对节点执行无障碍点击动作（ACTION_CLICK；long=true 时 ACTION_LONG_CLICK），不是触摸。id 取自 mode=a11y 的 observe 结果里节点的 id 字段，只对最近一次观测有效；visual、llm 观测没有 id，不能用，需要时先 observe(mode=a11y)。只有 act 含 click（长按需 longpress）的节点能点。不会自动改用坐标点击：应用拒绝或节点失效时返回错误，错误里给出该节点的 at，可用 tap 点击。节点动作只表示系统接受了请求，应用可能没有处理，结果里的“效果：”才说明界面是否变化。
