---
id: tool.global_action
version: 2
vars: []
meta:
  param.action: back 返回、home 主页、recents 最近任务、notifications 通知栏、quick_settings 快捷设置、lock 锁屏
---
执行系统全局动作。
注入模式有两种：shell 注入（等同真实触摸）和无障碍注入（部分应用会忽略）。当前模式见运行状态里的“坐标操作当前模式”，结果里的“执行：…”写明本次实际模式以及回退原因。
