---
id: flow.author.tools
version: 1
vars: []
---
ask_user|向用户提问并等待回答。question 是问题，options 是供选择的答案（尽量提供）。
inspect_screen|只读地观察手机当前屏幕，返回屏幕内容。mode 为 a11y、visual 或 llm。
demonstrate|让智能体在手机上真实操作一遍来示范目标，返回动作序列。调用前会征得用户同意。goal 是要示范的目标。
list_tools|列出流程里可用的工具及其参数。
list_flows|列出已有流程的名称、说明与参数。
read_flow|读取一个已有流程的完整 JSON，flow 是名称或 ID。
list_endpoints|列出已登记的接口名称与地址。
memory_search|只读检索记忆，了解用户偏好和联系人信息。
propose_flow|提交流程草稿，flow 是完整的流程 JSON。返回校验问题，修正后再次提交。
