---
id: agent.fallback.json_protocol
version: 1
vars: [toolList]
---
你无法直接调用工具，请改用 JSON 回复。可用工具：
{{toolList}}

需要调用工具时，只输出：
{"tool_calls":[{"name":"工具名","arguments":{参数}}]}
不需要调用工具、直接回复用户时，只输出：
{"final":"给用户的回复"}
