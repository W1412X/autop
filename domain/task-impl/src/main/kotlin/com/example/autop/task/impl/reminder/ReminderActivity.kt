/**
 * 锁屏全屏提醒（docs/tech/07 §4.4）：任务名、触发原因与三个动作
 * （执行=解锁后重入队；稍后可选 5/10/30 分钟，来自 `task.reminder.snoozeOptions`；跳过）。
 * View 实现（task-impl 不引 Compose，questions.md N15x）。
 */
package com.example.autop.task.impl.reminder

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.impl.R
import com.example.autop.task.impl.RunControl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 全屏提醒页。 */
public class ReminderActivity : Activity() {
    private lateinit var control: RunControl
    private lateinit var configProvider: ConfigProvider
    private lateinit var lockReminder: LockReminder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = EntryPointAccessors.fromApplication(this, ReminderEntryPoint::class.java)
        control = entry.runControl()
        configProvider = entry.configProvider()
        lockReminder = entry.lockReminder()
        val reminder = configProvider.current()[TaskConfig.REMINDER]
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(PAD, PAD, PAD, PAD)
            }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.reminder_title)
                textSize = TITLE_SP
                gravity = Gravity.CENTER
            },
        )
        root.addView(
            TextView(this).apply {
                text = runSummary()
                gravity = Gravity.CENTER
                setPadding(0, PAD / 2, 0, PAD / 2)
            },
        )
        root.addView(
            Button(this).apply {
                text = getString(R.string.reminder_action_execute)
                setOnClickListener { act { control.requeueNow(it) } }
            },
        )
        val snoozeRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        reminder.snoozeOptions.forEach { minutes ->
            snoozeRow.addView(
                Button(this).apply {
                    text = getString(R.string.reminder_snooze_minutes, minutes)
                    setOnClickListener { act { id -> control.snooze(id, minutes) } }
                },
            )
        }
        root.addView(snoozeRow)
        root.addView(
            Button(this).apply {
                text = getString(R.string.reminder_action_skip)
                setOnClickListener { act { control.cancelWaiting(it) } }
            },
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 等待中的第一个 Run 执行动作后收口。 */
    private fun act(block: (RunId) -> Unit) {
        val runId =
            pendingRunId() ?: run {
                finish()
                return
            }
        block(runId)
        lockReminder.dismiss(runId)
        finish()
    }

    private fun pendingRunId(): RunId? = intent.getStringExtra(ReminderActionReceiver.EXTRA_RUN_ID)?.let { RunId(it) }

    private fun runSummary(): String = getString(R.string.reminder_body_hint)

    private companion object {
        const val PAD = 48
        const val TITLE_SP = 22f
    }
}

/** 提醒页手动注入入口（普通 Activity 非 ComponentActivity，不能用 `@AndroidEntryPoint`）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderEntryPoint {
    fun runControl(): RunControl

    fun configProvider(): ConfigProvider

    fun lockReminder(): LockReminder
}
