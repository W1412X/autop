/**
 * AgentHost 通知按钮的广播接收器（docs/tech/07 §4.5）：暂停 / 停止当前 Run。
 */
package com.example.autop.task.impl.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.RunId
import com.example.autop.task.RunService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 通知动作接收器。 */
@AndroidEntryPoint
public class AgentHostActionsReceiver : BroadcastReceiver() {
    @Inject
    internal lateinit var runService: RunService

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.let { RunId(it) } ?: return
        when (intent.action) {
            ACTION_PAUSE -> runService.pause(runId)
            ACTION_CANCEL -> runService.cancel(runId)
        }
    }

    public companion object {
        public const val ACTION_PAUSE: String = "com.example.autop.task.impl.action.PAUSE"
        public const val ACTION_CANCEL: String = "com.example.autop.task.impl.action.CANCEL"
        public const val EXTRA_RUN_ID: String = "runId"
    }
}
