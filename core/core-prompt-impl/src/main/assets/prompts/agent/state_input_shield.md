---
id: agent.state.input.shield
version: 1
vars: [on, off]
optionalVars: [on, off]
---
触摸盾：{{#if on}}执行期间拦截用户触摸；ask_user、暂停时自动放开，用户可以操作手机{{/if}}{{#if off}}已关闭，执行期间用户触摸可能打断操作{{/if}}
