---
id: tool.observe
version: 2
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
quality.suggest 出现时说明这次结果信息不足，可以换成它建议的 mode。
