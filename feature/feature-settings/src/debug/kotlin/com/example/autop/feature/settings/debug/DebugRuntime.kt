/**
 * 调试会话运行时（docs/tech/08 §4.6）：整个调试会话共用一个 [DebugRunValues] 与
 * [DebugThreadStore]，并提供快照夹具导出（docs/tech/05 §11：Download/autop/，可选脱敏）。
 */
package com.example.autop.feature.settings.debug

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.autop.agent.ToolContext
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ScreenSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DebugRuntime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val runValues = DebugRunValues()

        val threadStore: DebugThreadStore = DebugThreadStore(File(context.cacheDir, "debug"))

        /** 构造本次执行的调试上下文（配置取当前快照）。 */
        fun context(config: ConfigSnapshot): ToolContext = DebugToolContext(config, threadStore, runValues)

        /** 导出快照夹具到 `Download/autop/`（docs/tech/05 §11）；返回文件名。 */
        fun exportSnapshot(
            fileName: String,
            text: String,
        ): String {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/autop")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        }
    }

/** 密码/敏感节点的导出脱敏替换值（docs/tech/05 §11"可选的脱敏替换"）。 */
private const val REDACTED_TEXT: String = "•••"

/** 导出前对文本做脱敏：PASSWORD / DATA_SENSITIVE 节点的文字替换为 [REDACTED_TEXT]。 */
internal fun redactSnapshot(snapshot: ScreenSnapshot): ScreenSnapshot =
    snapshot.copy(nodes = snapshot.nodes.map(::redactNode))

private fun redactNode(node: NodeSnapshot): NodeSnapshot {
    val sensitive = node.flags and (NodeFlags.PASSWORD or NodeFlags.DATA_SENSITIVE) != 0
    if (!sensitive) return node
    return node.copy(
        text = node.text?.let { REDACTED_TEXT },
        contentDesc = node.contentDesc?.let { REDACTED_TEXT },
        hint = node.hint?.let { REDACTED_TEXT },
        stateDesc = node.stateDesc?.let { REDACTED_TEXT },
    )
}
