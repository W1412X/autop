/**
 * 解锁广播接收器（2026-11-09 修复"条件不触发"）：ACTION_USER_PRESENT → [UnlockBus]，
 * 喂给 `trigger.screen_unlock` 事件源——此前总线无人发布、解锁条件永不触发。
 */
package com.example.autop.task.impl.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 解锁事件接收器（manifest 注册，仅响应系统解锁广播）。 */
public class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        UnlockBus.events.tryEmit(Unit)
    }
}
