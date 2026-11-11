---
id: tool.scroll_node
version: 1
vars: []
meta:
  param.id: a11y 观测里节点的 id 字段
  param.direction: up、down、left 或 right
  param.steps: 连续滚动的次数，默认 1；建议 1–3
---
对可滚动节点（act 含 scroll）执行无障碍滚动动作，每步后等稳定并判断是否移动，到头会提示。方向含义同 scroll（up 是手指往上滑，看下方内容）。同样要求 a11y 观测的 id；被拒或失效时返回错误，不会补手势，可改用 scroll 加该节点的 at。
