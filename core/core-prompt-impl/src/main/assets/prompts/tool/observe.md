---
id: tool.observe
version: 7
vars: [modeList]
meta:
  param.mode: 观测方式；省略时按下面的优先级自动选择
---
获取当前屏幕。可用方式按优先级从高到低：
{{modeList}}
省略 mode 时自动选择：排在前面的方式信息足够就用它，不够才依次换下一种；结果第一行会写明用了哪种、跳过了哪些及原因。两种方式的结果同样可信。只有确实需要某种方式时才显式传 mode，显式传入时不会自动更换。
结果是 JSON：每个元素有 bounds=[x1,y1,x2,y2]，可操作的元素有 at=[x,y]（推荐点击点）。坐标都是内容区 0–1000，原点在左上角，x 向右、y 向下；所有操作工具都用这个坐标。a11y 观测里可操作元素还带 id 字段，用于 tap_node、scroll_node 直接操作该节点，只对最近一次观测有效；visual 观测没有 id。
quality.insufficient 表示这次结果信息不足（原因见结果末尾说明），quality.suggest 是下一种可用方式；quality.truncated 表示元素太多、列表被截断，需要滚动或分区域查看。
