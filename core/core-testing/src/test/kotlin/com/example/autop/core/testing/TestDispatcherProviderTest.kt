package com.example.autop.core.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test

class TestDispatcherProviderTest {
    @Test
    fun `all dispatchers point to the injected test dispatcher`() {
        val dispatcher = StandardTestDispatcher()
        val provider = TestDispatcherProvider(dispatcher)
        assertThat(provider.main).isSameInstanceAs(dispatcher)
        assertThat(provider.io).isSameInstanceAs(dispatcher)
        assertThat(provider.default).isSameInstanceAs(dispatcher)
    }
}
