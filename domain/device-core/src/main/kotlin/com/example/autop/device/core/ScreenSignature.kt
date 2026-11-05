/**
 * 快照语义签名（ADR-0007 §2.6）：判定"即时快照"与某次序列化时的屏幕是否同一屏。
 * 覆盖全部影响序列化输出的字段（不含 capturedAt）；签名相同 ⇒ 序列化结果相同 ⇒
 * 短 ID 当前视图仍有效。字符串全量比较（不做哈希，避免碰撞误判）。
 */
package com.example.autop.device.core

import com.example.autop.device.ScreenSnapshot

/** 快照签名。 */
public object ScreenSignature {
    /** [snapshot] 的语义签名（data class toString 全字段，逐项拼接）。 */
    public fun of(snapshot: ScreenSnapshot): String =
        buildString {
            append(snapshot.foregroundPackage).append('\n')
            append(snapshot.activity).append('\n')
            append(snapshot.screenWidth).append('x').append(snapshot.screenHeight).append('\n')
            append(snapshot.rotation).append('\n')
            append(snapshot.keyguardLocked).append('\n')
            append(snapshot.contentArea).append('\n')
            append(snapshot.truncated).append('\n')
            for (window in snapshot.windows) append(window).append('\n')
            for (node in snapshot.nodes) append(node).append('\n')
        }
}
