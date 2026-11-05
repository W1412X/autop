---
id: agent.tool.error_result
version: 1
vars: [code, message, hint]
optionalVars: [hint]
---
错误 {{code}}：{{message}}{{#if hint}}
建议：{{hint}}{{/if}}
