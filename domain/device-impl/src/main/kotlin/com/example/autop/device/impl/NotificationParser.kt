/**
 * 通知解析（docs/tech/05 §9）：extras 文本 + MessagingStyle 最近消息。
 */
package com.example.autop.device.impl

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.autop.device.NotificationInfo
import com.example.autop.device.NotificationMessage

/** StatusBarNotification → [NotificationInfo]。 */
internal object NotificationParser {
    /** 解析一条通知；文本均按不可信输入透传。 */
    fun parse(
        context: Context,
        sbn: StatusBarNotification,
        maxMessages: Int,
    ): NotificationInfo {
        val extras = sbn.notification.extras
        return NotificationInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            postedAt = sbn.postTime,
            title = textOf(extras, Notification.EXTRA_TITLE),
            text = textOf(extras, Notification.EXTRA_TEXT),
            bigText = textOf(extras, Notification.EXTRA_BIG_TEXT),
            subText = textOf(extras, Notification.EXTRA_SUB_TEXT),
            conversationTitle = textOf(extras, Notification.EXTRA_CONVERSATION_TITLE),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = styleMessages(sbn.notification, maxMessages),
            category = sbn.notification.category,
            isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
    }

    /** MessagingStyle 最近 [maxMessages] 条（旧→新）；非会话通知返回空。 */
    @Suppress("DEPRECATION") // senderPerson 不可用的 androidx.core 版本退回 Message.sender
    private fun styleMessages(
        notification: Notification,
        maxMessages: Int,
    ): List<NotificationMessage> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        return style?.messages.orEmpty().takeLast(maxMessages).map { msg ->
            NotificationMessage(
                sender = msg.sender?.toString(),
                text = msg.text?.toString(),
                time = msg.timestamp,
            )
        }
    }

    private fun textOf(
        extras: android.os.Bundle,
        key: String,
    ): String? = extras.getCharSequence(key)?.toString()

    private fun appLabel(
        context: Context,
        packageName: String,
    ): String? =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
}
