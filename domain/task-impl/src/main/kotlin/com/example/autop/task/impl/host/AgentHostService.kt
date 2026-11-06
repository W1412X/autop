/**
 * 运行期前台服务（docs/tech/07 §4.5）：`specialUse` 类型常驻通知——
 * 空闲显示已启用触发任务数，运行中显示当前步骤与"暂停/停止"按钮；
 * 无活动 Run、非 `task.host.persistent` 且未配对无线调试通道（N203 shell 保活）时 `stopSelf`。
 * 业务逻辑不在此处（Service 只保活与通知）。
 */
package com.example.autop.task.impl.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellKeepAlive
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunService
import com.example.autop.task.impl.R
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.store.RunStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** AgentHost 前台服务。 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // N114：Service 生命周期/通知构建函数随功能增长，不可再减。
public class AgentHostService : Service() {
    @Inject
    internal lateinit var runService: RunService

    @Inject
    internal lateinit var runStore: RunStore

    @Inject
    internal lateinit var db: TasksDb

    @Inject
    internal lateinit var config: ConfigProvider

    @Inject
    internal lateinit var hostStatus: AgentHostStatusImpl

    @Inject
    internal lateinit var logger: Logger

    @Inject
    internal lateinit var shellInput: ShellInput

    @Inject
    internal lateinit var shellKeepAlive: ShellKeepAlive

    // lazy：注入发生在 onCreate 之后，字段初始化时 logger 尚未就绪。
    private val scope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + logger.exceptionHandler("AgentHostService"))
    }
    private var observeJob: Job? = null

    /** 有活跃 Run 时持有的 CPU 唤醒锁；息屏后网络请求与协程不会被休眠打断。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        hostStatus.setRunning(true)
        createChannel()
        startForegroundHost()
        shellKeepAlive.start(scope)
        observeJob =
            scope.launch {
                combine(runService.observeActive(), runStore.observeUnfinished(), shellInput.paired) {
                    active,
                    unfinished,
                    paired,
                    ->
                    Triple(active, unfinished.size, paired)
                }.collect { (active, unfinishedCount, paired) ->
                    syncWakeLock(held = active != null)
                    if (shouldStop(active, unfinishedCount, paired)) {
                        stopSelf()
                        return@collect
                    }
                    updateNotification(active)
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        syncWakeLock(held = false)
        shellKeepAlive.stop()
        observeJob?.cancel()
        scope.cancel()
        hostStatus.setRunning(false)
        super.onDestroy()
    }

    /** 按是否有活跃 Run 获取或释放唤醒锁（幂等）；最长持有时间取 `task.host.wakeLockMaxMs`，到点系统自动释放。 */
    private fun syncWakeLock(held: Boolean) {
        val current = wakeLock
        if (held && current?.isHeld != true) {
            val lock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.acquire(config.current()[TaskConfig.HOST].wakeLockMaxMs)
            wakeLock = lock
        } else if (!held && current?.isHeld == true) {
            current.release()
            wakeLock = null
        }
    }

    /** 空闲收口（N203）：无活动 Run、无未完 Run、未配对 shell 通道且非常驻时停服。 */
    private fun shouldStop(
        active: ActiveRunInfo?,
        unfinishedCount: Int,
        paired: Boolean,
    ): Boolean =
        when {
            active != null || unfinishedCount > 0 -> false
            paired -> false
            else -> !config.current()[TaskConfig.HOST].persistent
        }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.host_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundHost() {
        val notification = buildNotification(null, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(active: ActiveRunInfo?) {
        val enabledCount = if (active == null) db.taskDao().countEnabled() else 0
        val notification = buildNotification(active, enabledCount)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** 构建通知（同步；计数由调用方提供，避免主线程查库）。 */
    private fun buildNotification(
        active: ActiveRunInfo?,
        enabledCount: Int,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        builder
            .setSmallIcon(R.drawable.host_notification_icon)
            .setOngoing(true)
        if (active == null) {
            builder
                .setContentTitle(getString(R.string.host_notification_title))
                .setContentText(getString(R.string.host_idle_text, enabledCount))
        } else {
            val step = active.currentStep
            val stepText =
                if (step != null) getString(R.string.host_running_step, step) else getString(R.string.host_running)
            builder
                .setContentTitle(getString(R.string.host_running_title, active.stepCount))
                .setContentText(stepText)
                .addAction(actionPause(active))
                .addAction(actionStop(active))
        }
        return builder.build()
    }

    private fun actionPause(active: ActiveRunInfo): Notification.Action {
        val intent =
            Intent(this, AgentHostActionsReceiver::class.java).apply {
                action = AgentHostActionsReceiver.ACTION_PAUSE
                putExtra(AgentHostActionsReceiver.EXTRA_RUN_ID, active.run.id.value)
            }
        val pending =
            PendingIntent.getBroadcast(
                this,
                REQUEST_PAUSE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action
            .Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.host_action_pause),
                pending,
            ).build()
    }

    private fun actionStop(active: ActiveRunInfo): Notification.Action {
        val intent =
            Intent(this, AgentHostActionsReceiver::class.java).apply {
                action = AgentHostActionsReceiver.ACTION_CANCEL
                putExtra(AgentHostActionsReceiver.EXTRA_RUN_ID, active.run.id.value)
            }
        val pending =
            PendingIntent.getBroadcast(
                this,
                REQUEST_CANCEL,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action
            .Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                getString(R.string.host_action_stop),
                pending,
            ).build()
    }

    private companion object {
        const val CHANNEL_ID = "agent_host"
        const val NOTIFICATION_ID = 4102
        const val REQUEST_PAUSE = 1
        const val REQUEST_CANCEL = 2

        /** 唤醒锁名称（`adb shell dumpsys power` 里可见，用于排查耗电）。 */
        const val WAKE_LOCK_TAG = "autop:run"
    }
}
