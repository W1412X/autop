package com.example.autop.core.config.impl

import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetsConfigLoaderTest {
    @Test
    fun `merges domain files into one tree ignoring file order`() {
        val files =
            listOf(
                ConfigAssetFile("bbb.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"bbb":{"x":2}}"""),
                ConfigAssetFile("aaa.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"aaa":{"y":1}}"""),
            )
        val tree = loadDefaultConfigTree(files)
        assertThat(tree.keys).containsExactly("aaa", "bbb")
        val aaa = tree["aaa"] as JsonObject
        assertThat((aaa["y"] as JsonPrimitive).int).isEqualTo(1)
    }

    @Test
    fun `duplicate domain across files is a startup failure even with different subtrees`() {
        val files =
            listOf(
                ConfigAssetFile("a.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"x":1}}"""),
                ConfigAssetFile("b.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"y":2}}"""),
            )
        val e = assertThrows(IllegalStateException::class.java) { loadDefaultConfigTree(files) }
        assertThat(e).hasMessageThat().contains("config key path conflict")
        assertThat(e).hasMessageThat().contains("'a'")
        assertThat(e).hasMessageThat().contains("b.json")
    }

    @Test
    fun `one file may contribute several domain keys when paths stay unique`() {
        val files =
            listOf(
                ConfigAssetFile("a.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"x":1},"b":{"y":2}}"""),
                ConfigAssetFile("c.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"c":{}}"""),
            )
        val tree = loadDefaultConfigTree(files)
        assertThat(tree.keys).containsExactly("a", "b", "c")
    }

    @Test
    fun `missing schemaVersion is a startup failure`() {
        val files = listOf(ConfigAssetFile("a.json", """{"a":{}}"""))
        val e = assertThrows(IllegalStateException::class.java) { loadDefaultConfigTree(files) }
        assertThat(e).hasMessageThat().contains("schemaVersion")
    }

    @Test
    fun `wrong schemaVersion is a startup failure`() {
        val files = listOf(ConfigAssetFile("a.json", """{"schemaVersion":${CURRENT_SCHEMA_VERSION + 1},"a":{}}"""))
        val e = assertThrows(IllegalStateException::class.java) { loadDefaultConfigTree(files) }
        assertThat(e).hasMessageThat().contains("schemaVersion")
    }

    @Test
    fun `invalid json is a startup failure`() {
        val files = listOf(ConfigAssetFile("a.json", """{"schemaVersion":$CURRENT_SCHEMA_VERSION,"a":{"""))
        val e = assertThrows(IllegalStateException::class.java) { loadDefaultConfigTree(files) }
        assertThat(e).hasMessageThat().contains("a.json")
    }
}
