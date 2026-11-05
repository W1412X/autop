---
id: tool.scroll
version: 2
vars: []
meta:
  param.direction: up、down、left 或 right
  param.amount: page 整页或 half 半页，默认 page
  param.x: 滚动区域内一点，0–1000
  param.y: 滚动区域内一点，0–1000
---
滚动屏幕。x、y 是要滚动区域里的任意一点（例如列表的 at），不填为屏幕中央。direction 是手指滑动的方向：up 是手指往上滑，用来查看下方的内容；down 用来查看上方的内容。
