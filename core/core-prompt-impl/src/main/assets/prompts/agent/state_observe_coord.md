---
id: agent.state.observe.coord
version: 1
vars: [content, full]
optionalVars: [full]
---
坐标基准：{{#if content}}内容区（不含状态栏和导航栏）{{/if}}{{#if full}}整个屏幕{{/if}}，0–1000
