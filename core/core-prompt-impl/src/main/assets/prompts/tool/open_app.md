---
id: tool.open_app
version: 1
vars: []
meta:
  param.package: 应用包名，如 com.tencent.mm
  param.name: 应用名称，不知道包名时使用
---
打开一个应用。package 与 name 二选一；按名称匹配到多个应用时会返回候选列表。
