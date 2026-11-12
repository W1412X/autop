/**
 * RestartRulesLoader（docs/design/tool_optimize/02 §13.3、06 §1.3）：
 * 正常加载 restart_rules.json；schemaVersion 不符、settleMs 越界、空包名抛错。
 */
package com.example.autop.device.impl.restart

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RestartRulesLoaderTest {
    @Test
    fun `loads the bundled rules`() {
        val rules = RestartRulesLoader(RuntimeEnvironment.getApplication()).get()

        assertThat(rules.schemaVersion).isEqualTo(1)
        assertThat(rules.protectedPackages).contains("com.android.systemui")
        assertThat(rules.protectedPackages).contains("com.android.settings")
        assertThat(rules.settleMs).isIn(0L..10_000L)
    }

    @Test
    fun `schema version mismatch throws`() {
        val thrown =
            runCatching {
                parseRestartRules("""{"schemaVersion":2,"protectedPackages":[],"settleMs":0}""")
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).contains("schemaVersion")
    }

    @Test
    fun `settleMs out of range throws`() {
        val thrown =
            runCatching {
                parseRestartRules("""{"schemaVersion":1,"protectedPackages":[],"settleMs":20000}""")
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).contains("settleMs")
    }

    @Test
    fun `blank protected package throws`() {
        val thrown =
            runCatching {
                parseRestartRules("""{"schemaVersion":1,"protectedPackages":[""],"settleMs":0}""")
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).contains("protectedPackages")
    }
}
