---
id: tool.observe
version: 3
vars: [modeList]
meta:
  param.mode: 观测方式，见上
  param.full: true 时返回完整结果，否则只返回与上次的差异（llm 总是完整）
  param.region: 仅 llm：只识别这块区域，[x1,y1,x2,y2]，0–1000
---
获取当前屏幕。mode 必填，可选方式按推荐顺序：
{{modeList}}
结果是 JSON：每个元素有 bounds=[x1,y1,x2,y2]，可操作的元素有 at=[x,y]（推荐点击点）。
坐标都是内容区 0–1000，原点在左上角，x 向右、y 向下；所有操作工具都用这个坐标。
a11y 结果里出现 quality.suggest 时，说明无障碍信息不足，可以换成它建议的 mode。视觉结果不附模式建议：视觉是本机廉价的兜底方式，识别结果够支撑下一步就继续；只有确实不够时再自行改用 llm（没有固定阈值，由你判断）。
