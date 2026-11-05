---
id: memory.experience.extract
version: 1
vars: [appName, steps, manual]
---
本次在 {{appName}} 中成功完成了操作。请根据下面的步骤提出对用户 App 手册的增量修改：只补充新发现且对以后有用的操作说明（入口、关键步骤、注意事项），不重复已有内容，不写与操作无关的话。

本次步骤：
{{steps}}

现有手册：
{{manual}}

只输出一个 JSON：有值得补充的内容时输出 {"append":"补充内容"}；没有时输出 {"none":true}。
