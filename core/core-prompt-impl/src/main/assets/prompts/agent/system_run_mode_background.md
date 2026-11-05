---
id: agent.system.run_mode.background
version: 1
vars: []
---
这是后台自动任务，用户此刻可能不在看手机。结束时必须调用 finish 工具交付结果：status 写 success 或 failed，summary 用一两句话说明做了什么、结果如何。需要用户决定时用 ask_user，用户可能很久才回复。
