---
id: flow.author.spec
version: 3
vars: [open, close]
---
# 流程 JSON 规格

顶层：{"format":"autop.flow","schemaVersion":1,"id":"flow_xxx","name":"名称","description":"一句话说明","params":[...],"settings":{...},"steps":[节点...]}

params 每项：{"name","type":"string|number|boolean|list|object|time|any","required","default","description","enum":[...],"secret"}。
settings：observeMode("a11y"|"visual"|"llm"，默认 a11y)、onFail、maxDurationMs、pauseAfterConsecutiveFailures(默认3)、pacing{"betweenNodesMs":数字或{"min","max"}}、budget{"maxLlmTokens","maxLlmCalls","maxApiCalls"}、allowedTools、trace("full"|"failures"|"none")、memoryLog{"enabled","keep"}。

## 变量与表达式
- 字符串里的 {{open}} 表达式 {{close}} 在运行时求值；整个字符串恰好是一个表达式时保留原类型。
- 内置：param.名称（参数）、trigger.字段（触发上下文，如 trigger.text）、run.id/startedAt/origin/flowName、last.ok/error/text（上一节点结果）。
- 运算符：! - * / % + - < <= > >= == != && ||；字面量 数字、'字符串'、true/false/null、[a,b]、{"k":v}；取值 a.b、a[0]。变量未定义会失败，使用前先 set。
- 函数：len upper lower trim contains startsWith endsWith replace split join substr indexOf regexMatch regexExtract regexReplace str num round floor ceil abs min max clamp random get first last slice head tail append prepend concat reverse unique pluck range has keys values merge set json toJson now formatTime hour minute weekday dayKey addMs if coalesce isNull isEmpty typeOf untrusted。

## 目标描述 target（点击、读取、等待用）
{"by":"text|icon|desc|hint|tag","value":"文字","match":"segment(默认)|equals|contains|startsWith|regex","tag":"控件类型","pick":"unique(默认)|first|last|nth|nearest|all","nth":序号(从1起),"within":{另一个target，限定在其内部},"region":[x1,y1,x2,y2](0-1000),"actionable":true,"fallback":{"at":[x,y],"requireTag","requireText"},"mode":"覆盖观测模式"}。
列表项文字常是"标题 · 摘要"拼接，segment 匹配会按" · "分段后逐段比较。

## 条件 cond（if、loop、wait 用，字段 kind 区分）
expr{expr} | screenText{op:contains|notContains|equals|regex,value} | element{op:exists|absent|checked|unchecked|enabled|disabled|focused,target} | app{value:包名,page} | screenChanged{since} | ime{shown} | device{metric:battery|charging|network|screenOn|locked,op:==|!=|<|<=|>|>=,value} | time{between:["HH:mm","HH:mm"],weekdays:[1..7]} | lastOk{node} | llm{prompt,screen,images,role} | all{of:[...]} | any{of:[...]} | not{of:条件}。

## 节点（字段 type 区分；公共字段 id name comment enabled onFail confirm timeoutMs trace pre）
- tool：{tool:"工具名",args:{...},target,target2,out,expect,anchor}。target 解析成坐标写入 x,y（swipe 为 x1,y1 与 target2 的 x2,y2）。expect：{kind:"screenChanged|text|textGone|element|elementGone|app",text,target,app,timeoutMs,cond} 动作后的结果契约；cond（条件对象，写法同 if 的 cond）非空时优先于 kind，描述"到达了什么状态"。anchor：{app,text,target} 恢复后判断是否回到原页面。不能用 finish、update_plan、note、history_recall、observe、run_flow。
- observe：{mode,out}，结果 {mode,app,page,nodes,text}。
- read：{from:"target|list|page|region|screenshot",target,attr:"text|desc|hint|state|checked|exists|bounds|count",region,out}。
- wait：{for:"time|text|gone|element|elementGone|change|stable|app|notification|cond|until",ms,value,page,target,since,package,contains,sender,cond,onTimeout:"fail|continue",out}。
- llm：{role:"main|light|vision",system,prompt,screen,images:[引用],output:{type:"text|json|label|bool|number",schema,labels},validators:[{kind:"maxLen|minLen|regex|notContains|oneOf|range|expr",...,message}],retry:{max,feedback},limits,temperature,onInvalid:{use:"默认值表达式"},out}。
- if：{cond,then:[...],else:[...]}；switch：{on:"表达式",cases:[{values:[...],steps:[...]}],default:[...]}。
- loop：{mode:"count|while|until|forever|each",count,cond,items,itemVar,indexVar,maxIterations,maxDurationMs,delayMs,continueOnFail,resumeKey,steps:[...]}；break；continue。
- call：{flow:"子流程名或id",args:{...},out}；子流程用 end 的 outputs 返回结果。
- agent：{goal,tools:[...],maxSteps,maxDurationMs,role,out}，把无法预先写成步骤的部分交给智能体。
- api：{endpoint:"已登记的接口名",method,path,query,headers,bodyType:"none|json|form|text",body,expectStatus,out}，结果 {status,headers,json,text}。
- set：{vars:{"名":"表达式"}}，按书写顺序求值。
- state：{op:"get|set|inc|delete",key,value,scope:"task|flow",ttlMs,resetDaily,default,out}，跨运行保存状态。
- log：{message,level}；end：{status:"success|failure",message,outputs:{"名":"表达式"}}。

## 前置条件 pre（公共字段）
{"pre":{"cond":条件,"policy":"fail|wait","timeoutMs":数字,"message":"失败说明"}}。进入节点前断言页面状态，不成立即判定节点失败并走 onFail；policy=wait 先等待条件成立，超时再失败。条件写法同 if 的 cond（kind：expr/screenText/element/app/screenChanged/ime/device/time/lastOk/all/any/not）。前置条件不支持 llm 判定。前置契约默认继承上一步的结果契约，不用写；只有继承来的条件太强，或循环体/分支的第一个节点，才显式写 pre（pre.inherit=false 表示不继承上一步的结果契约）。

## 失败处理 onFail
{"retry":{"max":3,"delayMs":数字或{"min","max"}},"handoff":{"goal","tools","maxSteps","maxDurationMs","role"},"steps":[兜底节点],"after":"retry|continue","final":"stop|skip|pause|succeed"}。顺序：retry → handoff → steps → final。confirm：{"message","timeoutMs","onTimeout":"skip|stop|proceed"}。
