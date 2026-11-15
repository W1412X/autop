/**
 * 随消息发送的图片（已压缩为 JPEG 字节）。聊天与 AI 创建页共用；移到 core-common 以便 task 与 flow 两个 API 都能引用。
 */
package com.example.autop.core.common

public class ImageAttachment(
    public val bytes: ByteArray,
    public val mime: String,
    public val width: Int,
    public val height: Int,
)
