/**
 * AI 创建会话的图片存储：图片写入 `dir/<owner>/`，路径作为 Block.Image 的 fileRef 写进会话消息，
 * 与对话图片一样持久。owner 为草稿 id（流程）或任务会话 id（任务），由所属会话在合适时机删除。
 */
package com.example.autop.core.common

import java.io.File
import java.util.UUID

public class AuthorImageStore(
    private val dir: File,
) {
    /** 把图片写入 owner 的目录，返回绝对路径。 */
    public fun save(
        image: ImageAttachment,
        owner: String,
    ): String {
        val ownerDir = File(dir, owner).apply { mkdirs() }
        val file = File(ownerDir, "img_${UUID.randomUUID()}.jpg")
        file.writeBytes(image.bytes)
        return file.absolutePath
    }

    /** 删除 owner 名下的全部图片（目录不存在则忽略）。 */
    public fun deleteOwner(owner: String) {
        File(dir, owner).deleteRecursively()
    }
}
