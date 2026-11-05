---
id: device.llm_parser.coord.pixels_yxyx
version: 1
vars: [w, h]
---
图片宽 {{w}} 像素、高 {{h}} 像素，坐标是像素整数，0 ≤ x < {{w}}，0 ≤ y < {{h}}。box = [上, 左, 下, 右]，整数，上 < 下，左 < 右；宽和高分别按各自方向计算，图片不是正方形也一样。
