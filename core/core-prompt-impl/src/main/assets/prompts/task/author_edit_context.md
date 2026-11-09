---
id: task.author.edit_context
version: 1
vars: [task]
---
正在修改的已有任务（保存时会更新这个任务，而不是新建）：
{{task}}

修改要求：只调整用户提到的部分，其余字段保持原值。如果用户想换执行方式或触发方式，按 propose_task 字段口径给出新值。删除任务只能由用户在任务页操作，不要承诺删除。
