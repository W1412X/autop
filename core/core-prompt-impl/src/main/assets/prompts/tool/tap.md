---
id: tool.tap
version: 3
vars: []
meta:
  param.x: 点击横坐标，0–1000
  param.y: 点击纵坐标，0–1000
  param.long: 是否长按，默认否
  param.wait_ms: 点击后等待界面稳定的最长时间（毫秒），一般不用填
---
按坐标点击。x、y 是内容区 0–1000 坐标，一般直接用观测里元素的 at。结果里的 hit 是实际点到的元素，和目标不一致说明点偏了。
