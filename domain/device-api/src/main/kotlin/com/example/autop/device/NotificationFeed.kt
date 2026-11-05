/**
 * 通知读取端口（docs/tech/05 §1.3、§9）：NotificationListenerService 的数据面。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 通知流。 */
public interface NotificationFeed {
    /** 新通知。 */
    public val posted: SharedFlow<NotificationInfo>

    /** 当前仍显示的通知（可按包名过滤）。 */
    public suspend fun active(packageName: String?): List<NotificationInfo>

    /** 历史通知（环形缓冲）。 */
    public suspend fun recent(
        packageName: String?,
        sinceMs: Long?,
        limit: Int,
    ): List<NotificationInfo>

    /** 监听服务是否连接。 */
    public val connected: StateFlow<Boolean>

    /**
     * 请求系统重新绑定监听服务（已授权但未连接时调用，如覆盖安装后系统未自动重绑；
     * N190：授权在而 connected=false 的"假无权限"场景靠它恢复）。
     */
    public fun requestRebind()
}

/** 一条通知（文本均不可信）。 */
@Serializable
public data class NotificationInfo(
    @SerialName("key") val key: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("appLabel") val appLabel: String?,
    @SerialName("postedAt") val postedAt: Long,
    @SerialName("title") val title: String?,
    @SerialName("text") val text: String?,
    @SerialName("bigText") val bigText: String?,
    @SerialName("subText") val subText: String?,
    @SerialName("conversationTitle") val conversationTitle: String?,
    @SerialName("isGroupConversation") val isGroupConversation: Boolean,
    /** MessagingStyle 最近若干条。 */
    @SerialName("messages") val messages: List<NotificationMessage>,
    @SerialName("category") val category: String?,
    @SerialName("isOngoing") val isOngoing: Boolean,
)

/** MessagingStyle 里的一条消息。 */
@Serializable
public data class NotificationMessage(
    @SerialName("sender") val sender: String?,
    @SerialName("text") val text: String?,
    @SerialName("time") val time: Long,
)
