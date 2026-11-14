/**
 * 策略拦截通知的动作（docs/tech/07 §7）：“我已手动完成，继续”→ resume；
 * “结束”→ cancel。
 */
package com.example.autop.task.impl.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.RunId
import com.example.autop.core.platform.DataSchemaState
import com.example.autop.task.impl.R
import com.example.autop.task.impl.RunControl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 拦截通知按钮的动作接收器。 */
@AndroidEntryPoint
public class NotifyActionReceiver : BroadcastReceiver() {
    @Inject internal lateinit var control: RunControl

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!DataSchemaState.isReady()) return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.let { RunId(it) } ?: return
        when (intent.action) {
            UserNotifierImpl.ACTION_RESUME ->
                control.resumeBlocked(runId, note = context.getString(R.string.notify_resume_note))
            UserNotifierImpl.ACTION_CANCEL -> control.cancelWaiting(runId)
        }
    }

    public companion object {
        public const val EXTRA_RUN_ID: String = "runId"
    }
}
