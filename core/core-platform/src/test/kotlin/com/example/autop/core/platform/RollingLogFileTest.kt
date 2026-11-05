package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingLogFileTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `append writes lines to current file and counts bytes`() {
        val logs = RollingLogFile(tmp.root)
        logs.append("hello")
        logs.append("world")
        assertThat(File(tmp.root, "autop.log").readText()).isEqualTo("hello\nworld\n")
        assertThat(logs.totalBytes()).isEqualTo(12L)
    }

    @Test
    fun `append creates directory when missing`() {
        val dir = File(tmp.root, "logs")
        val logs = RollingLogFile(dir)
        logs.append("x")
        assertThat(File(dir, "autop.log").exists()).isTrue()
        assertThat(logs.directoryPath).isEqualTo(dir.absolutePath)
    }

    @Test
    fun `file rotates to history when exceeding limit`() {
        val logs = RollingLogFile(tmp.root)
        val big = "a".repeat(600 * 1024)
        logs.append(big)
        logs.append(big)
        // 第二行超 1MB 上限：旧文件转 autop.1.log，新行从 autop.log 重新写。
        assertThat(File(tmp.root, "autop.1.log").readText()).isEqualTo("$big\n")
        assertThat(File(tmp.root, "autop.log").readText()).isEqualTo("$big\n")
    }

    @Test
    fun `history keeps at most three files`() {
        val logs = RollingLogFile(tmp.root)
        val big = "b".repeat(600 * 1024)
        repeat(8) { logs.append(big) }
        assertThat(File(tmp.root, "autop.4.log").exists()).isFalse()
        assertThat(File(tmp.root, "autop.3.log").exists()).isTrue()
    }

    @Test
    fun `clear removes all files and returns freed bytes`() {
        val logs = RollingLogFile(tmp.root)
        logs.append("abc")
        val freed = logs.clear()
        assertThat(freed).isEqualTo(4L)
        assertThat(logs.totalBytes()).isEqualTo(0L)
        assertThat(File(tmp.root, "autop.log").exists()).isFalse()
    }

    @Test
    fun `readAll concatenates content oldest first`() {
        val logs = RollingLogFile(tmp.root)
        val big = "a".repeat(600 * 1024)
        logs.append(big + "1")
        logs.append(big + "2") // 超限滚动：第一条进 autop.1.log，第二条在新 autop.log
        val text = logs.readAll()
        assertThat(text.indexOf("1")).isLessThan(text.indexOf("2"))
    }

    @Test
    fun `readAll returns empty when no files`() {
        assertThat(RollingLogFile(tmp.root).readAll()).isEmpty()
    }

    @Test
    fun `append never throws when directory is a file`() {
        // 日志设施自身绝不抛异常：目录被同名文件占位时 append 静默吞错。
        val blocker = tmp.newFile("blocked")
        val logs = RollingLogFile(blocker)
        logs.append("x")
        assertThat(logs.totalBytes()).isEqualTo(0L)
    }
}
