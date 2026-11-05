---
id: tool.screenshot
version: 4
vars: []
meta:
  param.region: 只截取该区域，{x1,y1,x2,y2} 坐标 0–1000（相对内容区）
  param.mark_point: 落点核对标注，{x,y} 坐标 0–1000（相对内容区），在截图上画十字准星
  param.reason: 截图用途说明，会记录到审计日志
---
截取当前屏幕并返回图片（已隐藏状态栏/导航栏，坐标以可见区为基准）。节点列表看不清或界面是图片、网页、游戏时使用。坐标点击拿不准落点时，传 mark_point 在截图上画出十字准星核对位置，确认命中目标后再用 tap 传 x,y 点击。
