---
id: tool.swipe
version: 4
vars: []
meta:
  param.x1: 起点横坐标，0–1000
  param.y1: 起点纵坐标，0–1000
  param.x2: 终点横坐标，0–1000
  param.y2: 终点纵坐标，0–1000
  param.duration_ms: 滑动时长（毫秒，必须大于 0），一般不用填
---
按坐标滑动，坐标同 tap。列表滚动优先用 scroll。
