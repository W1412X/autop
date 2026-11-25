package com.example.autop.core.common

/** 后台运行开关不可用的原因；顺序与界面提示优先级一致。 */
public enum class BackgroundRunBlock {
    RUNNING,
    UNSUPPORTED,
    CHANNEL_DISCONNECTED,
    ;

    public companion object {
        public fun of(
            supported: Boolean,
            channelConnected: Boolean,
            running: Boolean,
        ): BackgroundRunBlock? =
            when {
                running -> RUNNING
                !supported -> UNSUPPORTED
                !channelConnected -> CHANNEL_DISCONNECTED
                else -> null
            }

        /** An unavailable selection must remain removable. Running still locks both directions. */
        public fun canToggle(
            block: BackgroundRunBlock?,
            checked: Boolean,
        ): Boolean = block == null || (checked && block != RUNNING)
    }
}
