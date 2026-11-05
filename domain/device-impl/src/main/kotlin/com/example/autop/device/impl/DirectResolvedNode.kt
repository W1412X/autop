/**
 * 直接持有的真实节点（docs/design/observation-modes-coordinate-actions §5.3）：
 * `input_text` 对当前焦点输入框执行 ACTION_SET_TEXT / IME 回车，动作结束由调用方关闭。
 */
package com.example.autop.device.impl

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ResolvedNode

/** [ResolvedNode] 的直接节点实现（不做路径重解析）。 */
internal class DirectResolvedNode(
    override val snapshot: NodeSnapshot,
    override val windowId: Int,
    private val node: AccessibilityNodeInfo,
) : ResolvedNode {
    /** 对真实节点执行无障碍动作；节点失效返回 false。 */
    override fun performAction(
        actionId: Int,
        args: Map<String, Any?>,
    ): Boolean {
        val bundle =
            Bundle().apply {
                args.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is CharSequence -> putCharSequence(key, value)
                        else -> Unit
                    }
                }
            }
        return node.refresh() && node.performAction(actionId, bundle)
    }

    override fun currentText(): String? = if (node.refresh()) node.text?.toString() else null

    /** 释放节点引用（API < 33 调用 recycle）。 */
    fun close() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            node.recycle()
        }
    }
}
