/**
 * 读取当前运行环境（无障碍、Shell 总闸与通道、通知使用权）为 [RunEnvironment]。
 * 调度器在任务开始时使用，AI 创作会话用它给草稿权限提示。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.task.RunEnvironment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class RunEnvironmentProvider
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val shellInput: ShellInput,
        private val accessibilityStatus: AccessibilityStatus,
        private val permissions: PermissionChecks,
    ) {
        /** 此刻的运行环境快照。 */
        public fun current(): RunEnvironment =
            RunEnvironment(
                accessibilityConnected = accessibilityStatus.connected.value,
                shellSwitchOn = configProvider.current()[FeaturesConfig.FEATURES].shellExec,
                shellConnected = shellInput.state.value == ShellState.Connected,
                notificationListenerGranted = permissions.canReadNotifications(),
            )
    }
