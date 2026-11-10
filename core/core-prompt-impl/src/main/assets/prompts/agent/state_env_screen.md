---
id: agent.state.env.screen
version: 1
vars: [off, locked, unlocked]
optionalVars: [off, locked, unlocked]
---
屏幕：{{#if off}}已熄灭{{/if}}{{#if locked}}已锁定，需要用户解锁后才能操作{{/if}}{{#if unlocked}}已解锁{{/if}}
