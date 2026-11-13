---
id: task.author.spec
version: 2
vars: [flows]
---
propose_task 字段口径：
- name：任务名，短。
- goal：自然语言目标——写清楚做什么、注意什么；对话中获得的提示（页面入口、易错点）一并写进去。
- triggerType：manual（手动）/ once（到点一次）/ cron（周期）/ condition（条件触发）。
- at：triggerType=once 时的时间，格式 `yyyy-MM-dd HH:mm` 或 ISO-8601。
- cron、timezone：triggerType=cron 时的 5 段表达式（分 时 日 月 周）与时区。
- source、condition：triggerType=condition 时的事件源 id 与判定条件。事件源：trigger.notification（收到通知）/ trigger.app_foreground（应用切前台）/ trigger.charging（充电）/ trigger.network（网络变化）/ trigger.screen_unlock（解锁屏幕）；condition 用自然语言写，如"通知内容包含'快递'"。
{{#if flows}}- executorFlow：绑定流程名（用 list_flows 确认存在）；留空=智能体按 goal 自主执行。
{{/if}}- needsScreen：需要屏幕与界面交互时 true。
- hint：补充提示（可选），经验性说明。

给出草稿后用一两句话说明取舍，让用户确认或继续修改。
