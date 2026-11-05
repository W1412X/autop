/**
 * 图片读取端口（docs/tech/03 §1）：消息模型只存文件引用，发送前由实现方读取字节并把长边
 * 限制到 [maxEdge]，适配器负责 base64。实现方（Android 侧）可用 Bitmap 缩放后重编码。
 */
package com.example.autop.llm

/** 读取结果（可直接 base64 的图片字节）。 */
public data class LoadedImage(
    val bytes: ByteArray,
    val mime: String,
)

public interface ImageLoader {
    /**
     * 读取 [fileRef] 指向的图片并把长边限制到 [maxEdge] 像素；读取失败返回 null。
     * [mime] 为消息模型中声明的类型，实现可按需重编码并以 [LoadedImage.mime] 返回实际类型。
     */
    public suspend fun load(
        fileRef: String,
        mime: String,
        maxEdge: Int,
    ): LoadedImage?
}
