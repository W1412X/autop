---
id: tool.notify_user
version: 3
vars: []
meta:
  param.title: 通知标题
  param.text: 通知正文
---
给用户发一条通知，后台任务汇报结果时使用。只发通知、不会有人回答；需要用户回答时用 ask_user。
通知权限未开启时返回失败（用户看不到通知），这时把要说的内容写进最终回复。
