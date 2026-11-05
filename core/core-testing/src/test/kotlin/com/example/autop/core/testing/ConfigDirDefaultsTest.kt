package com.example.autop.core.testing

import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 锁定与 core-config-impl `loadDefaultConfigTree` 一致的镜像语义（questions.md N16）。 */
class ConfigDirDefaultsTest {
    @Test
    fun `files merge by filename order into one tree`() {
        val dir = tempDir()
        dir.resolve("bbb.json").writeText("""{"schemaVersion":$CURRENT_SCHEMA_VERSION,"bbb":{"x":2}}""")
        dir.resolve("aaa.json").writeText("""{"schemaVersion":$CURRENT_SCHEMA_VERSION,"aaa":{"y":1}}""")
        val tree = loadConfigDirDefaults(dir)
        assertThat(tree.keys).containsExactly("aaa", "bbb").inOrder()
    }

    @Test
    fun `wrong schema version is a failure`() {
        val dir = tempDir()
        dir.resolve("a.json").writeText("""{"schemaVersion":${CURRENT_SCHEMA_VERSION + 1},"a":{}}""")
        val e = assertThrows(IllegalStateException::class.java) { loadConfigDirDefaults(dir) }
        assertThat(e).hasMessageThat().contains("schemaVersion")
    }

    @Test
    fun `duplicate key path across files is a failure`() {
        val dir = tempDir()
        dir.resolve("a.json").writeText("""{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"x":1}}""")
        dir.resolve("b.json").writeText("""{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"y":2}}""")
        val e = assertThrows(IllegalStateException::class.java) { loadConfigDirDefaults(dir) }
        assertThat(e).hasMessageThat().contains("conflict")
    }

    @Test
    fun `missing directory is a failure`() {
        val e = assertThrows(IllegalStateException::class.java) { loadConfigDirDefaults(File("ghost-dir-not-exist")) }
        assertThat(e).hasMessageThat().contains("autop.configDir")
    }

    private fun tempDir(): File = Files.createTempDirectory("autop-config-dir").toFile().apply { deleteOnExit() }
}
