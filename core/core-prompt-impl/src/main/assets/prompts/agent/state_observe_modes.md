---
id: agent.state.observe.modes
version: 1
vars: [modes, auto, none]
optionalVars: [none, auto]
---
观测方式（按优先级）：{{modes}}{{#if none}}无可用方式{{/if}}；省略 mode 时依次尝试：{{auto}}
