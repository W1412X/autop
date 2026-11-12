/**
 * 每个 Run 的装配分组（docs/tech/04 §3.1）：历史渲染、上下文组装、
 * 工具路由与运行期引擎；TurnLoop 及其协作类按需取用。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.runtime.tools.ToolRouter

/** Run 装配分组。 */
internal class RunParts(
    val renderer: HistoryRenderer,
    val assembler: ContextAssembler,
    val router: ToolRouter,
    val engines: RunEngines,
)
