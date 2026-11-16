package com.example.autop.device.impl.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellDangerRulesTest {
    private val rules =
        parseShellDangerRules(
            """
            {
              "schemaVersion": 1,
              "rules": [
                {"id": "delete", "labelKey": "key_delete", "patterns": ["rm -r", "--force", " dd ", "pm clear"]},
                {"id": "self", "labelKey": "key_self", "patterns": ["{self}", "adbd"]}
              ]
            }
            """.trimIndent(),
        )

    @Test
    fun `substring pattern matches flag variants`() {
        assertThat(rules.match("rm -rf /sdcard/x", SELF)?.ruleId).isEqualTo("delete")
        assertThat(rules.match("RM -R /sdcard/x", SELF)?.ruleId).isEqualTo("delete")
    }

    @Test
    fun `whitespace between tokens is collapsed before matching`() {
        assertThat(rules.match("pm   clear\tcom.foo", SELF)?.ruleId).isEqualTo("delete")
    }

    @Test
    fun `pattern with edge spaces only matches whole tokens`() {
        assertThat(rules.match("dd if=/dev/zero of=/x", SELF)?.ruleId).isEqualTo("delete")
        assertThat(rules.match("echo add the value", SELF)).isNull()
        assertThat(rules.match("ls; dd if=x", SELF)?.ruleId).isEqualTo("delete")
    }

    @Test
    fun `self placeholder is replaced with the app package`() {
        assertThat(rules.match("am force-stop com.example.autop", SELF)?.ruleId).isEqualTo("self")
        assertThat(rules.match("am force-stop com.other.app", SELF)).isNull()
    }

    @Test
    fun `ordinary read only commands do not match`() {
        assertThat(rules.match("getprop ro.build.version.sdk", SELF)).isNull()
        assertThat(rules.match("ls /sdcard | grep foo", SELF)).isNull()
    }

    @Test
    fun `first matching rule in file order wins`() {
        assertThat(rules.match("rm -r adbd", SELF)?.ruleId).isEqualTo("delete")
    }

    @Test
    fun `schema version other than current is rejected`() {
        val failure =
            runCatching { parseShellDangerRules("""{"schemaVersion": 2, "rules": []}""") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("schemaVersion")
    }

    @Test
    fun `blank pattern or empty rule list is rejected`() {
        val blank =
            runCatching {
                parseShellDangerRules(
                    """{"schemaVersion": 1, "rules": [{"id": "x", "labelKey": "x", "patterns": [" "]}]}""",
                )
            }.exceptionOrNull()
        assertThat(blank).isInstanceOf(IllegalStateException::class.java)
        val empty = runCatching { parseShellDangerRules("""{"schemaVersion": 1, "rules": []}""") }.exceptionOrNull()
        assertThat(empty).isInstanceOf(IllegalStateException::class.java)
    }

    private companion object {
        const val SELF = "com.example.autop"
    }
}
