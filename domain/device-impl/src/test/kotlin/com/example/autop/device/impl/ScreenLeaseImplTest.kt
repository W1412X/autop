package com.example.autop.device.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScreenLeaseImplTest {
    @Test
    fun `tryAcquire takes the lease and release frees it`() =
        runTest {
            val lease = ScreenLeaseImpl()
            assertThat(lease.tryAcquire("run-a", timeoutMs = 100)).isTrue()
            assertThat(lease.holder.value).isEqualTo("run-a")
            lease.release("run-a")
            assertThat(lease.holder.value).isNull()
        }

    @Test
    fun `another owner times out while the lease is held`() =
        runTest {
            val lease = ScreenLeaseImpl()
            assertThat(lease.tryAcquire("run-a", timeoutMs = 100)).isTrue()
            assertThat(lease.tryAcquire("run-b", timeoutMs = 100)).isFalse()
            assertThat(lease.holder.value).isEqualTo("run-a")
        }

    @Test
    fun `same owner may acquire again without blocking`() =
        runTest {
            val lease = ScreenLeaseImpl()
            assertThat(lease.tryAcquire("run-a", timeoutMs = 100)).isTrue()
            assertThat(lease.tryAcquire("run-a", timeoutMs = 100)).isTrue()
            lease.release("run-a")
            assertThat(lease.holder.value).isNull()
        }

    @Test
    fun `release by a non-holder is a no-op`() =
        runTest {
            val lease = ScreenLeaseImpl()
            assertThat(lease.tryAcquire("run-a", timeoutMs = 100)).isTrue()
            lease.release("run-b")
            assertThat(lease.holder.value).isEqualTo("run-a")
            assertThat(lease.tryAcquire("run-b", timeoutMs = 50)).isFalse()
        }

    @Test
    fun `lease acquired after another owner releases`() =
        runTest {
            val lease = ScreenLeaseImpl()
            lease.tryAcquire("run-a", timeoutMs = 100)
            lease.release("run-a")
            assertThat(lease.tryAcquire("run-b", timeoutMs = 100)).isTrue()
            assertThat(lease.holder.value).isEqualTo("run-b")
        }
}
