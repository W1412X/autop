---
id: tool.edit_flow
version: 1
vars: []
meta:
  param.flow: 要修改的流程 id 或名称
  param.name: 新的流程名称
  param.description: 新的流程说明
  param.set_step: 修改一步：{node_id, tool?, args?, target?}，给出的字段覆盖原值
  param.add_step: 新增一步：{tool, args?, target?, after?}，after 为插入位置的顶层节点 id，缺省加到末尾
  param.remove_step: 要删除的节点 id
---
修改既有流程，只传需要变更的字段；流程只能修改、不能通过工具删除（删除由用户在流程页操作）。
节点 id 与现有步骤先用 list_flows 查看（steps[].id）；set_step 只支持 tool 节点，add_step / remove_step 一次调用一个步骤。改完简要说明变更点。结果里带详情与编辑入口。
