---
id: task.draft.generate
version: 1
vars: [description, sources, now, timezone]
---
把用户的一句话描述转换成任务草稿 JSON。只输出 JSON，不要输出其它文字。

当前时间：{{now}}（时区 {{timezone}}）
可用的触发来源及其字段：
{{sources}}

用户描述：
{{description}}

草稿 JSON 的字段（与任务的 name/goal/trigger/needsScreen/allowedTools 对应）：
- name：任务名称，简短概括。
- goal：自然语言目标，说明完成的标准。
- trigger：触发方式，三选一：
  - 手动：{"type":"manual"}
  - 定时（一次性）：{"type":"schedule","spec":{"type":"once","atMillis":毫秒时间戳}}
  - 定时（周期）：{"type":"schedule","spec":{"type":"cron","expr":"5 段 cron 表达式","zoneId":"时区，如 Asia/Shanghai"}}
  - 条件：{"type":"condition","sourceId":"触发来源 id","filter":过滤组,"llmCondition":"难以用规则表达的判定条件，可省略","cooldownMs":两次触发的最小间隔毫秒,"dailyLimit":每日最多触发次数,"dedupeField":"用事件的哪个字段去重，可省略"}
- needsScreen：是否需要屏幕信息，默认 true。
- allowedTools：允许使用的工具 id 数组，省略表示全部启用工具。

过滤组 filter：{"mode":"ALL"或"ANY","clauses":[子句,...],"groups":[嵌套过滤组,...]}；子句：{"field":"事件字段名","op":"EQUALS|NOT_EQUALS|CONTAINS|NOT_CONTAINS|STARTS_WITH|REGEX|IN|EXISTS","value":"比较值，IN 时多个值用 | 分隔，EXISTS 时忽略","ignoreCase":true或false}。
规则过滤尽量用确定的字段（应用包名、发送者、关键词），只把难以用规则表达的部分写进 llmCondition。

示例 1（每天早上 9 点定时查看天气并提醒）：
{"name":"早间天气提醒","goal":"打开天气应用查看今天天气，用 notify_user 告知用户","trigger":{"type":"schedule","spec":{"type":"cron","expr":"0 9 * * *","zoneId":"Asia/Shanghai"}},"needsScreen":true}

示例 2（微信里张三发来消息时提醒）：
{"name":"重要消息提醒","goal":"读取该条消息内容并用 notify_user 通知用户","trigger":{"type":"condition","sourceId":"trigger.notification","filter":{"mode":"ALL","clauses":[{"field":"package","op":"EQUALS","value":"com.tencent.mm","ignoreCase":false},{"field":"text","op":"CONTAINS","value":"张三"}],"groups":[]},"cooldownMs":60000,"dailyLimit":20,"dedupeField":"key"},"needsScreen":true}
