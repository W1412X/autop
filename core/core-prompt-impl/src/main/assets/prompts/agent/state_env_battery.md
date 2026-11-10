---
id: agent.state.env.battery
version: 1
vars: [battery, charging]
optionalVars: [charging]
---
电量：{{battery}}%{{#if charging}}，充电中{{/if}}
