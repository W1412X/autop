/**
 * 记忆工具的参数结构（docs/tech/06 §3 的参数表）：稳定 id + `toolSpec` DSL。
 * 参数说明取模板 `meta["param.<名>"]`。
 */
package com.example.autop.memory.impl.tools

import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec

/** 4 个记忆工具的 spec。 */
public object MemoryToolSpecs {
    /** `tool.memory_list`。 */
    public val memoryList: ToolSpec =
        toolSpec(name = "memory_list", descriptionPromptId = "tool.memory_list") {
            string("path", required = true)
            integer("depth")
            integer("offset")
            integer("limit")
        }

    /** `tool.memory_read`。 */
    public val memoryRead: ToolSpec =
        toolSpec(name = "memory_read", descriptionPromptId = "tool.memory_read") {
            string("path", required = true)
            integer("offset")
            integer("limit")
        }

    /** `tool.memory_search`。 */
    public val memorySearch: ToolSpec =
        toolSpec(name = "memory_search", descriptionPromptId = "tool.memory_search") {
            string("query", required = true)
            string("path_prefix")
            integer("k")
        }

    /** `tool.memory_write`。 */
    public val memoryWrite: ToolSpec =
        toolSpec(name = "memory_write", descriptionPromptId = "tool.memory_write") {
            string("path", required = true)
            string("mode", required = true, enum = listOf("CREATE", "UPDATE", "APPEND"))
            string("content", required = true)
            string("title")
            string("summary")
            array("tags")
            string("reason", required = true)
            obj("evidence") {
                string("package")
                string("excerpt")
            }
        }
}
