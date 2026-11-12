---
id: device.hint.intent_app_candidates
version: 1
vars: [query, notFound, ambiguous, candidates]
optionalVars: [query, notFound, ambiguous, candidates]
---
{{#if notFound}}没有找到应用「{{query}}」。可以先用 list_apps 查包名。{{/if}}{{#if ambiguous}}「{{query}}」匹配到多个应用，请改用包名指定：{{candidates}}{{/if}}
