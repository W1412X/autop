---
id: tool.scroll_node
version: 2
vars: []
meta:
  param.id: a11y 观测里节点的 id 字段
  param.direction: up、down、left 或 right
  param.steps: 连续滚动的次数，默认 1，最多 5；中间不检查内容，建议 1–3
  param.wait_ms: 动作后等待的毫秒数；缺省按设置（默认 5000），最大 30000。页面切换慢时调大，连续输入等确定不需要等待时可以调小
---
对可滚动节点（act 含 scroll）执行无障碍滚动动作。方向含义同 scroll（up 是手指往上滑，看下方内容）。steps 大于 1 时连续执行，中间不判断是否到头；滚完用 observe 确认内容是否变化。
同样要求 a11y 观测的 id；被拒或失效时返回错误，不会补手势，可改用 scroll 加该节点的 at。某一步失败时，执行记录会写明已经完成了几步。
