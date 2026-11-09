---
id: tool.update_task
version: 1
vars: []
meta:
  param.task: 要修改的任务 id 或任务名称
  param.name: 新的任务名称
  param.goal: 新的自然语言目标
  param.trigger_type: 新的触发方式：manual / once / cron / condition；不传则保持原触发方式
  param.at: trigger_type=once 时的执行时间，格式 yyyy-MM-dd HH:mm 或 ISO-8601
  param.cron: trigger_type=cron 时的 5 段表达式（分 时 日 月 周）
  param.timezone: cron 的时区（如 Asia/Shanghai），缺省系统时区
  param.source: trigger_type=condition 时的事件源 id
  param.condition: trigger_type=condition 时的自然语言判定条件
  param.flow: 绑定流程名称；留空=解除流程绑定改由智能体执行
  param.needs_screen: 是否需要屏幕与界面交互
  param.enabled: 是否启用
---
修改已有任务，只传需要变更的字段，其余保持原值。任务只能修改、不能通过工具删除（删除由用户在任务页操作）。
先用 list_tasks 确认任务名称；改完简要说明变更点。结果里带有任务详情与编辑入口，界面会渲染成快捷入口。
