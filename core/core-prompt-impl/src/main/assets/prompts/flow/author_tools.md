---
id: flow.author.tools
version: 2
vars: []
---
ask_user|向用户提问并等待回答。question 是问题，options 是供选择的答案（尽量提供）。
inspect_screen|只读地观察手机当前屏幕，返回屏幕内容。mode 为 a11y 或 visual。
demonstrate|让智能体在手机上真实操作一遍来示范目标，返回证据包（逐步的动作前后差异、命中元素与契约候选）。调用前会征得用户同意。goal 是要示范的目标。
list_tools|列出流程里可用的工具及其参数。
list_flows|列出已有流程的名称、说明与参数。
read_flow|读取一个已有流程的完整 JSON，flow 是名称或 ID。
list_endpoints|列出已登记的接口名称与地址。
memory_search|只读检索记忆，了解用户偏好和联系人信息。
read_evidence|读取证据包里某一步的完整证据（step 为证据步序号，省略返回全部摘要）：动作前后的屏幕文字、差异与全部契约候选。
check_contract|在证据上判定一个条件是否成立。cond 是流程条件 JSON，steps 是证据步序号数组（省略为全部）；返回每步动作前后的真假，供试写结果契约。
propose_flow|提交流程草稿，flow 是完整的流程 JSON；trace 可选给出节点 id 到证据步序号的对应（如 {"n1":0,"n2":1}），用于契约核验。返回校验问题，修正后再次提交。
