package com.example.autop.core.common

import kotlinx.serialization.Serializable

/**
 * 运行模式三档（ADR-0040）：仅聊天 < 标准模式 < 完全模式。
 *
 * 声明顺序即档位高低，高档位包含低档位的全部能力。[id] 是持久化与配置使用的稳定字符串，发布后不能改。
 * 工具的最低档位见 agent-api 的 `Tool.minMode`；工具只在 Run 当前档位不低于它时可见。
 */
@Serializable
public enum class RunMode(
    public val id: String,
) {
    /** 只对话，不提供屏幕工具，也不提供 shell。 */
    CHAT_ONLY("chat_only"),

    /** 定制的屏幕、设备、记忆等工具，不含 shell。 */
    STANDARD("standard"),

    /** 标准模式加 `run_shell`，普通命令直接执行。 */
    FULL("full"),
    ;

    /** 本模式是否包含 [required] 档位的能力。 */
    public fun includes(required: RunMode): Boolean = this >= required

    public companion object {
        /** 按稳定 id 解析；未知 id 返回 null，由调用方决定回退值。 */
        public fun fromId(id: String?): RunMode? = entries.firstOrNull { it.id == id }
    }
}
