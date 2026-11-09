/**
 * 流程类工具结果的结构化跳转入口：对话页把 `data.links` 渲染成快捷入口。
 *
 * link.kind 取值：flow_ai（draftId，可带 flowId；也可只带 flowId 直接改既有流程）/
 * flow_detail、flow_edit（flowId）。
 */
package com.example.autop.flow.impl.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 草稿结果：新草稿只能去 AI 编辑页继续改；修订草稿带上原流程时还可看详情与手动编辑。 */
internal fun draftData(
    draftId: String,
    flowId: String? = null,
): JsonObject =
    buildJsonObject {
        put("draftId", draftId)
        flowId?.let { put("flowId", it) }
        put(
            "links",
            buildJsonArray {
                if (flowId == null) {
                    add(flowAiLink(draftId = draftId))
                } else {
                    add(flowAiLink(draftId = draftId, flowId = flowId))
                    add(flowLink(kind = "flow_detail", flowId = flowId))
                    add(flowLink(kind = "flow_edit", flowId = flowId))
                }
            },
        )
    }

/** 修订已直接生效的结果：AI 编辑、查看详情或手动编辑。 */
internal fun flowData(flowId: String): JsonObject =
    buildJsonObject {
        put("flowId", flowId)
        put(
            "links",
            buildJsonArray {
                add(flowAiLink(flowId = flowId))
                add(flowLink(kind = "flow_detail", flowId = flowId))
                add(flowLink(kind = "flow_edit", flowId = flowId))
            },
        )
    }

private fun flowAiLink(
    draftId: String? = null,
    flowId: String? = null,
): JsonObject =
    buildJsonObject {
        put("kind", "flow_ai")
        draftId?.let { put("draftId", it) }
        flowId?.let { put("flowId", it) }
    }

private fun flowLink(
    kind: String,
    flowId: String,
): JsonObject =
    buildJsonObject {
        put("kind", kind)
        put("flowId", flowId)
    }
