---
id: agent.state.navigation
version: 1
vars: [apps, none, preferredAuto, preferredAsk, preferredApp, preferredMissing]
optionalVars: [apps, none, preferredAuto, preferredAsk, preferredApp, preferredMissing]
---
导航应用（intent 的 navigate）：{{#if apps}}已安装 {{apps}}{{/if}}{{#if none}}没有检测到地图应用，只能使用系统 geo 链接{{/if}}；用户设置：{{#if preferredAuto}}自动（按优先级选择已安装的应用）{{/if}}{{#if preferredAsk}}每次询问用户{{/if}}{{#if preferredApp}}{{preferredApp}}{{/if}}{{#if preferredMissing}}（未安装，将自动选择）{{/if}}
