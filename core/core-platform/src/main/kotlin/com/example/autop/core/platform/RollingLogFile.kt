package com.example.autop.core.platform

import com.example.autop.core.common.LogFiles
import java.io.File

/**
 * 滚动日志文件（docs/tech/02 §4.4，questions.md N165）：
 * `files/logs/autop.log` 追加写，超单文件上限滚动为 `autop.1.log`（历史最多 [HISTORY_FILES] 个，
 * 合计上限约 (HISTORY_FILES+1) × 单文件上限）。全部 IO 兜底吞错——日志设施自身绝不抛异常，
 * 崩溃路径上也要能写。
 */
internal class RollingLogFile(
    private val dir: File,
) : LogFiles {
    private val current = File(dir, FILE_NAME)

    override val directoryPath: String
        get() = dir.absolutePath

    /** 追加一行（含换行）；超限先滚动再写。 */
    @Synchronized
    override fun append(line: String) {
        runCatching {
            ensureDir()
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (current.length() + bytes.size > MAX_FILE_BYTES) rotate()
            current.appendBytes(bytes)
        }
    }

    @Synchronized
    override fun totalBytes(): Long = runCatching { files().sumOf { it.length() } }.getOrDefault(0L)

    /** 全部文件内容拼接（最旧历史在前、当前文件最后）；单文件读失败跳过，整体兜底空串。 */
    @Synchronized
    override fun readAll(): String =
        runCatching {
            files().reversed().joinToString("") { file ->
                runCatching { file.readText() }.getOrDefault("")
            }
        }.getOrDefault("")

    @Synchronized
    override fun clear(): Long {
        var freed = 0L
        for (file in files()) {
            runCatching {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    /** 滚动：删最旧历史，`autop.k.log` 顺次后移，当前文件变 `autop.1.log`。 */
    private fun rotate() {
        File(dir, historyName(HISTORY_FILES)).delete()
        for (index in HISTORY_FILES - 1 downTo 1) {
            File(dir, historyName(index)).renameTo(File(dir, historyName(index + 1)))
        }
        current.renameTo(File(dir, historyName(1)))
    }

    private fun files(): List<File> = listOf(current) + (1..HISTORY_FILES).map { File(dir, historyName(it)) }

    private fun historyName(index: Int): String = "autop.$index.log"

    private companion object {
        const val FILE_NAME = "autop.log"
        const val MAX_FILE_BYTES = 1L * 1024 * 1024
        const val HISTORY_FILES = 3
    }
}
