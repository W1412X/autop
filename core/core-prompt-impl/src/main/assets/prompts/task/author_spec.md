---
id: task.author.spec
version: 4
vars: [flows]
---
propose_task 是合并更新：只传需要修改的字段，没传的字段保持草稿原值；可选字段传空串表示清空。第一次给草稿时，没传的字段取默认值。

propose_task 字段口径：
- name：任务名，短。
- goal：自然语言目标——写清楚做什么、注意什么；对话中获得的提示（页面入口、易错点）一并写进去。
- triggerType：manual（手动）/ once（到点一次）/ cron（周期）/ condition（条件触发）。
- at：triggerType=once 时的时间，格式 `yyyy-MM-dd HH:mm` 或 ISO-8601。
- cron、timezone：triggerType=cron 时的 5 段表达式（分 时 日 月 周）与时区。
- source：triggerType=condition 时的事件源 id。先用 list_trigger_sources 查看可用事件源、字段与示例值。
- filter：triggerType=condition 时的字段过滤条件，JSON 文本（也可以直接传 JSON 对象），结构示例 `{"mode":"ALL","clauses":[{"field":"package","op":"EQUALS","value":"com.tencent.mm"}]}`。能用字段判断的条件（包名、发送人、正文关键词、充电状态）都写在这里；字段取值拿不准时用 sample_events 看真实样例。EXISTS 只需要 field 与 op，不需要 value。
- condition：triggerType=condition 时，filter 表达不了的语义，用自然语言写（如"对方是在问我有没有空"）。能用 filter 写的内容不要放在这里。filter 和 condition 至少给一个。
{{#if flows}}- executorFlow：绑定流程名（用 list_flows 确认存在）；留空=智能体按 goal 自主执行。
{{/if}}- needsScreen：需要屏幕与界面交互时 true。
- backgroundScreen：用户明确要求虚拟屏时设为 true。选定后通道不可用会结束当次运行，真实屏需要用户单独选择。
- runMode：standard（标准）或 full（完全）。完全模式需要 Shell 执行总闸开启且无线调试已连接，原始 Shell 仅在真实屏运行可用。
- hint：补充提示（可选），经验性说明。
propose_task 返回的 warnings 列出当前缺失的权限或通道，要原样告诉用户；issues 不为空时草稿不能保存，先按 issues 修正。

给出草稿后用一两句话说明取舍，让用户确认或继续修改。

