package com.example.autop.device.impl.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellCommandRulesTest {
    @Test
    fun `ordinary commands including shell structure are accepted`() {
        // 不做命令内容审查：管道、引号、su 字样都只是普通字符。
        assertThat(ShellCommandRules.problemOf("ls -l /sdcard | grep \"a b\"", 2048)).isNull()
        assertThat(ShellCommandRules.problemOf("input tap 1 2 && su", 2048)).isNull()
        assertThat(ShellCommandRules.problemOf("echo a\nb\tc\r", 2048)).isNull()
    }

    @Test
    fun `empty command is rejected`() {
        assertThat(ShellCommandRules.problemOf("", 2048)).isNotNull()
    }

    @Test
    fun `command longer than the byte limit is rejected`() {
        assertThat(ShellCommandRules.problemOf("a".repeat(2049), 2048)).isNotNull()
        assertThat(ShellCommandRules.problemOf("a".repeat(2048), 2048)).isNull()
    }

    @Test
    fun `byte limit counts utf8 bytes not characters`() {
        // 每个汉字 3 字节：683 个字符 = 2049 字节，应超限。
        assertThat(ShellCommandRules.problemOf("中".repeat(683), 2048)).isNotNull()
        assertThat(ShellCommandRules.problemOf("中".repeat(682), 2048)).isNull()
    }

    @Test
    fun `nul and c0 control characters are forbidden except tab newline and carriage return`() {
        assertThat(ShellCommandRules.isForbidden(0)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x01)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x1B)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x09)).isFalse()
        assertThat(ShellCommandRules.isForbidden(0x0A)).isFalse()
        assertThat(ShellCommandRules.isForbidden(0x0D)).isFalse()
    }

    @Test
    fun `del c1 and bidi control characters are forbidden`() {
        assertThat(ShellCommandRules.isForbidden(0x7F)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x85)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x202E)).isTrue()
        assertThat(ShellCommandRules.isForbidden(0x2066)).isTrue()
        assertThat(ShellCommandRules.isForbidden('A'.code)).isFalse()
        assertThat(ShellCommandRules.isForbidden('中'.code)).isFalse()
    }

    @Test
    fun `command containing a forbidden code point is rejected with its position`() {
        val problem = ShellCommandRules.problemOf("echo \u202E x", 2048)
        assertThat(problem).contains("U+202E")
    }
}
