---
id: tool.restart_app
version: 2
vars: []
meta:
  param.package: 要重启的应用包名，缺省时使用当前前台应用
---
强制结束一个应用的进程，然后重新打开它。用于应用卡死、页面无法恢复的情况。需要 shell 通道已连接；不可用时会返回失败，请改用 global_action back 或 home 再用 open_app。
受保护包名（系统界面等）、本应用自身与非法包名不允许结束，会返回 device.restart_denied，请换一个应用。
