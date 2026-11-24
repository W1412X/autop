---
id: agent.state.input.shield
version: 1
vars: [on, off]
optionalVars: [on, off]
---
触摸盾：{{#if on}}执行期间拦截用户触摸；ask_user、暂停时自动放开，用户可以操作手机{{/if}}{{#if off}}已关闭，执行期间不拦截用户触摸{{/if}}
