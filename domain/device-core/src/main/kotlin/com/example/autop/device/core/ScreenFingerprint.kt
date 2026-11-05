/**
 * 屏幕指纹（docs/tech/05 §3.3）：判断两次观测是否同一屏。
 */
package com.example.autop.device.core

import com.example.autop.device.ScreenNode
import java.security.MessageDigest

/** 指纹计算。 */
public object ScreenFingerprint {
    /** `sha1(foregroundPackage + "|" + activity + "|" + sorted(节点摘要).joinToString("\n")).take(12)`。 */
    public fun of(
        foregroundPackage: String?,
        activity: String?,
        nodes: List<ScreenNode>,
    ): String {
        val payload =
            buildString {
                append(foregroundPackage.orEmpty())
                append('|')
                append(activity.orEmpty())
                append('|')
                append(nodes.map(::summaryOf).sorted().joinToString("\n"))
            }
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }

    /** 节点内容摘要（不含 kids/key/bounds 的像素无关字段）。 */
    private fun summaryOf(node: ScreenNode): String =
        listOf(
            node.tag,
            node.text.orEmpty(),
            node.icon.orEmpty(),
            node.desc.orEmpty(),
            node.hint.orEmpty(),
            node.state.orEmpty(),
            node.cls.orEmpty(),
            node.vid.orEmpty(),
            node.act.joinToString(","),
            node.st.joinToString(","),
            "${node.bounds.l},${node.bounds.t},${node.bounds.r},${node.bounds.b}",
            node.at?.let { "${it.x},${it.y}" }.orEmpty(),
            node.more.orEmpty(),
        ).joinToString("|")

    private const val HASH_CHARS = 12
}
