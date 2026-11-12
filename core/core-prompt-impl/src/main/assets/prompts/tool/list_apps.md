---
id: tool.list_apps
version: 3
vars: []
meta:
  param.query: 按应用名称或包名过滤
  param.limit: 最多返回条数，缺省 50（最小 1）
---
查询已安装应用，每行返回名称与包名。结果被截断时会写明总数，可用 query 缩小范围。
