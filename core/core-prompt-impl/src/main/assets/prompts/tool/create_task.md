---
id: tool.create_task
version: 1
vars: []
meta:
  param.name: 任务名称，简短概括
  param.goal: 自然语言目标，写清楚做什么、注意什么、完成标准
  param.trigger_type: 触发方式：manual / once / cron / condition，缺省 manual
  param.at: trigger_type=once 时的执行时间，格式 yyyy-MM-dd HH:mm 或 ISO-8601
  param.cron: trigger_type=cron 时的 5 段表达式（分 时 日 月 周）
  param.timezone: cron 的时区（如 Asia/Shanghai），缺省系统时区
  param.source: trigger_type=condition 时的事件源 id
  param.condition: trigger_type=condition 时的自然语言判定条件
  param.flow: 绑定流程名称（先用 list_flows 确认存在）；留空=智能体按 goal 自主执行
  param.needs_screen: 是否需要屏幕与界面交互，默认 true
  param.enabled: 是否启用，默认 true
---
直接创建一个任务，保存后按触发器生效。用户明确要做某件事时使用；只是讨论方案时先不要创建。
先调用 list_tasks 检查是否已有同名或同类任务。删除只能由用户在任务页操作，不要承诺删除。
结果里带有任务详情与编辑入口，界面会渲染成快捷入口，不需要在正文里贴 id。
