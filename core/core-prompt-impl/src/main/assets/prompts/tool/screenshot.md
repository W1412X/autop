---
id: tool.screenshot
version: 6
vars: []
meta:
  param.region: 只截取该区域，{x1,y1,x2,y2} 坐标 0–1000（相对内容区）
  param.mark_point: 落点核对标注，{x,y} 坐标 0–1000（相对内容区），在截图上画十字准星
  param.reason: 截图用途说明，会记录到审计日志
---
截取当前屏幕并返回图片（已隐藏状态栏和导航栏，坐标以可见区为基准）。用于核对落点或看像素级细节，不能代替 observe。拿不准落点时传 mark_point 在截图上画出十字准星，确认命中目标后再用 tap 点击。
