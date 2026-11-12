---
id: device.intent.result
version: 1
vars: [alarm, timer, calendar, dial, smsDraft, share, navigate, settings, detail]
optionalVars: [alarm, timer, calendar, dial, smsDraft, share, navigate, settings, detail]
---
已交给系统处理：{{#if alarm}}设置闹钟 {{detail}}{{/if}}{{#if timer}}设置计时器 {{detail}}{{/if}}{{#if calendar}}新建日程 {{detail}}（需要在日历界面确认保存）{{/if}}{{#if dial}}打开拨号盘 {{detail}}（不会拨出）{{/if}}{{#if smsDraft}}生成短信草稿 {{detail}}（不会发送，需要在短信界面确认）{{/if}}{{#if share}}分享文字 {{detail}}{{/if}}{{#if navigate}}导航到 {{detail}}{{/if}}{{#if settings}}打开设置页 {{detail}}{{/if}}。是否办成以后续界面为准。
