package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [parseListenerPackages] 单测（N190：通知使用权授权名单解析）。 */
class ParseListenerPackagesTest {
    @Test
    fun `parses component names to package names`() {
        val value = "com.foo/.BarListener:com.baz/com.baz.inner.MyListener"
        assertThat(parseListenerPackages(value)).containsExactly("com.foo", "com.baz")
    }

    @Test
    fun `null and blank yield empty set`() {
        assertThat(parseListenerPackages(null)).isEmpty()
        assertThat(parseListenerPackages("")).isEmpty()
    }

    @Test
    fun `malformed entries are tolerated`() {
        // 容错：无 '/' 的片段按整段包名处理，空片段忽略。
        assertThat(parseListenerPackages("com.foo::/cls")).containsExactly("com.foo")
        assertThat(parseListenerPackages("com.bar")).containsExactly("com.bar")
    }
}
