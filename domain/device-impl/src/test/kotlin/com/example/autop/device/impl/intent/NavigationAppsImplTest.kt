package com.example.autop.device.impl.intent

import android.content.Context
import com.example.autop.core.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NavigationAppsImplTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val impl =
        NavigationAppsImpl(
            context = context,
            rules = NavigationAppsRulesLoader(context),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
        )

    @Test
    fun `known lists the rule table by priority`() {
        assertThat(impl.known().map { it.id }).containsExactly("amap", "baidu", "tencent", "google").inOrder()
        assertThat(impl.known().first().packageName).isEqualTo("com.autonavi.minimap")
    }

    @Test
    fun `nothing is reported installed on a bare device`() =
        runTest {
            assertThat(impl.installed()).isEmpty()
        }
}
