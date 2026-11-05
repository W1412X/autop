/**
 * 线程文件管理（docs/tech/04 §4）：`files/threads/<threadId>/img/` 与
 * `files/threads/<threadId>/blob/`；`fileRef` 是相对 `filesDir` 的路径。
 */
package com.example.autop.agent.store

import java.io.File

/** 大 payload 的列值前缀（fileRef 为相对 filesDir 的路径）。 */
internal const val PAYLOAD_FILE_PREFIX: String = "@file:"

/** 线程附件与大 payload 文件。 */
public class ThreadFiles(
    private val filesDir: File,
) {
    private val root = File(filesDir, "threads")

    private fun dirOf(
        threadId: String,
        kind: String,
    ): File = File(File(root, threadId), kind)

    /** 大 payload 落文件：`threads/<threadId>/blob/p<seq>.json`。 */
    fun writePayload(
        threadId: String,
        seq: Long,
        json: String,
    ): String {
        val dir = dirOf(threadId, "blob")
        dir.mkdirs()
        val file = File(dir, "p$seq.json")
        file.writeText(json)
        return refOf(file)
    }

    /** 保存二进制附件；图片扩展名进 `img/`，其余进 `blob/`。 */
    fun saveBlob(
        threadId: String,
        name: String,
        bytes: ByteArray,
    ): String {
        val kind = if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) "img" else "blob"
        val dir = dirOf(threadId, kind)
        dir.mkdirs()
        val file = uniqueFile(dir, sanitize(name))
        file.writeBytes(bytes)
        return refOf(file)
    }

    /** 按 fileRef 读文本（大 payload）。 */
    fun readText(ref: String): String = resolve(ref).readText()

    /** 删除线程的全部文件。 */
    fun deleteThread(threadId: String) {
        File(root, threadId).deleteRecursively()
    }

    private fun resolve(ref: String): File = File(filesDir, ref)

    private fun refOf(file: File): String = file.relativeTo(filesDir).invariantSeparatorsPath

    private fun sanitize(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return base.ifEmpty { "blob" }
    }

    private fun uniqueFile(
        dir: File,
        name: String,
    ): File {
        var file = File(dir, name)
        var i = 1
        while (file.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            file = File(dir, "$stem-$i.$ext".removeSuffix("."))
            i += 1
        }
        return file
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
