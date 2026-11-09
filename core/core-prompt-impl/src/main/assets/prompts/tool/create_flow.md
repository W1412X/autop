---
id: tool.create_flow
version: 1
vars: []
meta:
  param.name: 流程名称（先用 list_flows 确认不重名）
  param.description: 流程用途的一句话说明
  param.steps: 步骤数组，每步 {tool, args, target}；tool 为工具名，args 为工具参数，target 为目标定位
---
按步骤定义直接创建流程并立即生效，不需要先执行一遍。用于把用户描述的固定操作序列保存为可重复运行的流程。
steps 就是工具调用序列：参数写进 args；tap 这类点击不要写坐标或屏幕节点 id，用 target 按内容定位（如 {"value":"发送"} 默认按文字，或 {"by":"desc","value":"返回"}）。
需要条件分支、循环、子流程等复杂结构时，先建简单骨架再用 edit_flow 改，或引导用户到“流程”页的 AI 编辑。结果里带详情与编辑入口。创建后简要说明流程做了什么。
