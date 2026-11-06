/**
 * 对话附带的图片（用户从相册选取）：字节已按 `ui.chat.attach*` 配置缩放压缩。
 * 新对话发送时线程尚未建立，所以传字节而不是文件引用，由运行服务落盘。
 */
package com.example.autop.task

/** 一张待发送的图片（[bytes] 为编码后的图像，[mime] 如 `image/jpeg`）。 */
public class ImageAttachment(
    public val bytes: ByteArray,
    public val mime: String,
    public val width: Int,
    public val height: Int,
)
