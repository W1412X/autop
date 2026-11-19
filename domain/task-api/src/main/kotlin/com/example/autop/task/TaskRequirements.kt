/**
 * 任务运行所需的权限与通道（标准/完全模式、需要屏幕、条件源权限）。
 *
 * 编辑页、AI 创作草稿与调度器共用这一套判定：编辑与创作只提示缺失项，调度器在任务开始时
 * 按 [TaskRequirements.blockingAtStart] 决定是否直接失败。纯 Kotlin，不依赖 Android。
 */
package com.example.autop.task

import com.example.autop.core.common.RunMode

/** 任务需要、但当前可能不满足的一项。 */
public enum class TaskRequirement {
    /** 无障碍服务已连接（需要屏幕的任务）。 */
    ACCESSIBILITY,

    /** Shell 执行总闸已开启（完全模式）。 */
    SHELL_SWITCH,

    /** shell 注入通道已连接（完全模式）。 */
    SHELL_CHANNEL,

    /** 通知使用权已授予（条件源 `trigger.notification` 需要）。 */
    NOTIFICATION_LISTENER,
}

/** 运行环境快照：各项权限或通道的当前状态。 */
public data class RunEnvironment(
    val accessibilityConnected: Boolean,
    val shellSwitchOn: Boolean,
    val shellConnected: Boolean,
    val notificationListenerGranted: Boolean,
)

public object TaskRequirements {
    /** 条件源声明的权限名（与 `TriggerSource.requiresPermission` 对应）。 */
    public const val PERMISSION_NOTIFICATION_LISTENER: String = "notification_listener"

    /**
     * 当前不满足的全部项。[sourcePermission] 是条件触发所用源的 `requiresPermission`（非条件任务传 null）。
     */
    public fun missing(
        needsScreen: Boolean,
        runMode: RunMode,
        sourcePermission: String?,
        env: RunEnvironment,
    ): List<TaskRequirement> =
        buildList {
            if (needsScreen && !env.accessibilityConnected) add(TaskRequirement.ACCESSIBILITY)
            if (runMode == RunMode.FULL) {
                when {
                    !env.shellSwitchOn -> add(TaskRequirement.SHELL_SWITCH)
                    !env.shellConnected -> add(TaskRequirement.SHELL_CHANNEL)
                }
            }
            if (sourcePermission == PERMISSION_NOTIFICATION_LISTENER && !env.notificationListenerGranted) {
                add(TaskRequirement.NOTIFICATION_LISTENER)
            }
        }

    /**
     * 任务开始时必须直接失败的项：完全模式缺 shell。无障碍断线可能是瞬时的，仍按调度器原有的
     * "等待无障碍连接"处理，不在这里失败。
     */
    public fun blockingAtStart(
        runMode: RunMode,
        env: RunEnvironment,
    ): TaskRequirement? =
        when {
            runMode != RunMode.FULL -> null
            !env.shellSwitchOn -> TaskRequirement.SHELL_SWITCH
            !env.shellConnected -> TaskRequirement.SHELL_CHANNEL
            else -> null
        }
}
