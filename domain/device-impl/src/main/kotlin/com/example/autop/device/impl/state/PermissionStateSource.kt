/**
 * 运行状态来源 `state.permissions`（agent-harness-overhaul WP3.5.4）：
 * 只为缺失的权限产出条目（权限恢复后条目消失，按 removed 上报）。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 权限状态来源。 */
@Singleton
public class PermissionStateSource
    @Inject
    constructor(
        private val permissions: PermissionChecks,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> =
            buildList {
                if (!permissions.canDrawOverlay()) {
                    add(StateEntry(KEY_OVERLAY, render(PROMPT_OVERLAY)))
                }
                if (!permissions.canReadNotifications()) {
                    add(StateEntry(KEY_NOTIFICATION_LISTENER, render(PROMPT_NOTIFICATION_LISTENER)))
                }
                if (!permissions.canPostNotifications()) {
                    add(StateEntry(KEY_POST_NOTIFICATIONS, render(PROMPT_POST_NOTIFICATIONS)))
                }
            }

        private fun render(id: String): String =
            prompts
                .render(id, emptyMap())
                .getOrNull()
                ?.text
                .orEmpty()

        internal companion object {
            const val ID: String = "state.permissions"

            const val KEY_OVERLAY = "overlay"
            const val KEY_NOTIFICATION_LISTENER = "notification_listener"
            const val KEY_POST_NOTIFICATIONS = "post_notifications"

            private const val PROMPT_OVERLAY = "agent.state.perm.overlay"
            private const val PROMPT_NOTIFICATION_LISTENER = "agent.state.perm.notification_listener"
            private const val PROMPT_POST_NOTIFICATIONS = "agent.state.perm.post_notifications"
        }
    }
