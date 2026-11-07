/**
 * `tool.memory_write`（docs/tech/06 §3）：写入记忆。
 *
 * evidence 说明从哪里得知（包名与摘录）；写入 `/apps/{package}/...` 时自动创建
 * `/apps/{package}` 目录（docs/tech/06 §4，应用名取自 [AppLauncher]）。
 */
package com.example.autop.memory.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOrigin
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.device.AppLauncher
import com.example.autop.memory.AppliedMode
import com.example.autop.memory.MemoryEvidence
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 记忆写工具。 */
@Singleton
public class MemoryWriteTool
    @Inject
    constructor(
        private val store: MemoryStore,
        private val appLauncher: AppLauncher,
    ) : Tool {
        override val id: String = "tool.memory_write"

        override val spec = MemoryToolSpecs.memoryWrite

        override val access: ToolAccess = ToolAccess.WRITE_MEMORY

        @Suppress("ReturnCount") // 出口按“路径/模式非法”错误码分支，是业务语义
        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val path = parsePath(args.str("path").orEmpty()) ?: return pathError()
            val mode =
                when (args.str("mode")?.uppercase()) {
                    "CREATE" -> WriteMode.CREATE
                    "UPDATE" -> WriteMode.UPDATE
                    "APPEND" -> WriteMode.APPEND
                    else -> return toolError("agent.invalid_args", "mode 必须是 CREATE/UPDATE/APPEND")
                }
            ensureAppDir(path, ctx)
            val write =
                MemoryWrite(
                    path = path,
                    mode = mode,
                    title = args.str("title"),
                    summary = args.str("summary"),
                    content = args.str("content").orEmpty(),
                    tags = args.strings("tags"),
                    source = sourceOf(ctx),
                    runId = ctx.runId,
                    // 流程写入 reason 默认补 flow:<流程名>（docs/tech/06 §3）；模型给了 reason 用模型的。
                    reason = args.str("reason") ?: ctx.originTag,
                    evidence = evidenceOf(args["evidence"] as? JsonObject),
                )
            return when (val r = store.write(write)) {
                is Outcome.Ok -> textOutput(describe(r.value.node, r.value.appliedMode, r.value.versionId))
                is Outcome.Err -> errorOutput(r.error)
            }
        }

        /** `/apps/{package}` 缺失时自动创建（title 用应用名，找不到就用包名）。 */
        private suspend fun ensureAppDir(
            path: MemoryPath,
            ctx: ToolContext,
        ) {
            val segments = path.segments()
            if (segments.size < APP_PATH_MIN_SEGMENTS || segments[0] != "apps") return
            val packageDir = MemoryPath.ROOT.child("apps").child(segments[1])
            if (store.get(packageDir) != null) return
            val appName =
                runCatching { appLauncher.listApps(null).firstOrNull { it.packageName == segments[1] }?.label }
                    .getOrNull() ?: segments[1]
            store.write(
                MemoryWrite(
                    path = packageDir,
                    mode = WriteMode.CREATE,
                    title = appName,
                    summary = "",
                    content = "",
                    source = sourceOf(ctx),
                    runId = ctx.runId,
                    reason = "auto-create app dir",
                ),
            )
        }

        /** 调用方是流程时来源记为 [MemorySource.FLOW]，否则为 agent。 */
        private fun sourceOf(ctx: ToolContext): MemorySource =
            if (ctx.origin == ToolOrigin.FLOW) MemorySource.FLOW else MemorySource.AGENT

        private fun evidenceOf(obj: JsonObject?): MemoryEvidence? =
            obj?.let {
                MemoryEvidence(
                    packageName = it.str("package"),
                    excerpt = it.str("excerpt").orEmpty(),
                )
            }

        private fun describe(
            node: MemoryNode,
            applied: AppliedMode,
            versionId: Long,
        ): String {
            val suffix =
                if (applied == AppliedMode.APPENDED_AS_SUPPLEMENT) {
                    "（目标是用户手写内容，agent 的更新已降级为补充段落，标题与摘要未变）"
                } else {
                    ""
                }
            return "已写入 ${node.path.value}：模式 $applied，新版本 v${node.version}（版本记录 #$versionId）$suffix"
        }

        private companion object {
            /** `/apps/{package}` 的路径段数（apps、包名两段起）。 */
            const val APP_PATH_MIN_SEGMENTS: Int = 3
        }
    }
