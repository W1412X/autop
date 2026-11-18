---
id: tool.create_task
version: 2
vars: []
meta:
  param.name: 任务名称，简短概括
  param.goal: 自然语言目标，写清楚做什么、注意什么、完成标准
  param.trigger_type: 触发方式：manual / once / cron / condition，缺省 manual
  param.at: trigger_type=once 时的执行时间，格式 yyyy-MM-dd HH:mm 或 ISO-8601
  param.cron: trigger_type=cron 时的 5 段表达式（分 时 日 月 周）
  param.timezone: cron 的时区（如 Asia/Shanghai），缺省系统时区
  param.source: trigger_type=condition 时的事件源 id，先用 list_trigger_sources 确认
  param.filter: trigger_type=condition 时的字段过滤条件，JSON 文本。结构示例 {"mode":"ALL","clauses":[{"field":"package","op":"EQUALS","value":"com.tencent.mm"}]}。mode 为 ALL（全部满足）或 ANY（任一满足），clauses 为条件列表，groups 可嵌套子组。op 取值见 list_trigger_sources，字段名必须属于该事件源
  param.condition: trigger_type=condition 时，filter 表达不了的语义，用自然语言写，如"对方是在问我有没有空"。能用 filter 写的内容不要放在这里
  param.flow: 绑定流程名称（先用 list_flows 确认存在）；留空=智能体按 goal 自主执行
  param.needs_screen: 是否需要屏幕与界面交互，默认 true
  param.enabled: 是否启用，默认 true
---
直接创建一个任务，保存后按触发器生效。用户明确要做某件事时使用；只是讨论方案时先不要创建。
先调用 list_tasks 检查是否已有同名或同类任务。删除只能由用户在任务页操作，不要承诺删除。
结果里带有任务详情与编辑入口，界面会渲染成快捷入口，不需要在正文里贴 id。

条件触发的写法：先用 list_trigger_sources 查看事件源的字段；字段取值写法拿不准时用 sample_events 看真实样例。能用字段判断的条件写进 filter（如包名等于某值、正文包含某词），只有 filter 表达不了的语义才写进 condition。filter 和 condition 至少给一个。
