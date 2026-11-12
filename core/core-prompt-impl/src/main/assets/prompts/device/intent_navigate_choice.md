---
id: device.intent.navigate_choice
version: 1
vars: [app, requested, preferred, auto, ask, onlyOne, askFallback, geo]
optionalVars: [app, requested, preferred, auto, ask, onlyOne, askFallback, geo]
---
{{#if app}}导航使用 {{app}}：{{/if}}{{#if requested}}按你指定的 map{{/if}}{{#if preferred}}用户在设置里选定{{/if}}{{#if auto}}自动按优先级选择已安装的应用{{/if}}{{#if ask}}用户刚刚选择{{/if}}{{#if onlyOne}}只安装了这一个导航应用{{/if}}{{#if askFallback}}询问没有得到回答，已按优先级选择{{/if}}{{#if geo}}没有检测到地图应用，使用系统 geo 链接（只显示位置，不一定进入导航）{{/if}}
