---
id: tool.open_app
version: 3
vars: []
meta:
  param.package: 应用包名，如 com.tencent.mm
  param.name: 应用名称，不知道包名时使用
  param.wait_ms: 动作后等待的毫秒数；缺省按设置（默认 5000），最大 30000。页面切换慢时调大，连续输入等确定不需要等待时可以调小
---
打开一个应用。package 与 name 二选一（同传时以 package 为准）。按名称匹配到多个应用时返回失败并列出候选，不会打开任何应用，请用 package 指定。
工具只把启动请求交给系统，应用是否已经打开、停在哪个页面，用 observe 确认。
