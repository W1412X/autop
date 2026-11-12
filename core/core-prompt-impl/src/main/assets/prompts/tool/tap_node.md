---
id: tool.tap_node
version: 2
vars: []
meta:
  param.id: a11y 观测里节点的 id 字段
  param.long: 是否长按，默认否
  param.wait_ms: 动作后等待的毫秒数；缺省按设置（默认 5000），最大 30000。页面切换慢时调大，连续输入等确定不需要等待时可以调小
---
对节点执行无障碍点击动作（ACTION_CLICK；long=true 时 ACTION_LONG_CLICK），不是触摸。id 取自 mode=a11y 的 observe 结果里节点的 id 字段，只对最近一次观测有效；visual 观测没有 id，不能用，需要时先 observe(mode=a11y)。只有 act 含 click（长按需 longpress）的节点能点。
不会自动改用坐标点击：应用拒绝或节点失效时返回错误，错误里给出该节点的 at，可用 tap 点击。节点动作被系统接受，不代表应用处理了它；需要确认时调用 observe。
