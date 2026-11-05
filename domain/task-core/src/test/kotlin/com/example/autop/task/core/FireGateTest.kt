package com.example.autop.task.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

/** [FireGate] 单测（docs/tech/07 §2）�?*/
internal class FireGateTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val empty = TriggerState()

    @Test
    fun passesWhenCold() {
        assertThat(
            FireGate.check(empty, "k1", nowMillis = 1000, cooldownMs = 500, dailyLimit = 3, zone = zone),
        ).isEqualTo(FireGate.Verdict.Pass)
    }

    @Test
    fun blocksWithinCooldown() {
        val state = empty.copy(lastFiredAt = 1000)
        val verdict = FireGate.check(state, "k1", nowMillis = 1400, cooldownMs = 500, dailyLimit = 3, zone = zone)
        assertThat(verdict).isEqualTo(FireGate.Verdict.Blocked(FireGate.Verdict.Reason.COOLDOWN))
    }

    @Test
    fun boundaryCooldownPasses() {
        val state = empty.copy(lastFiredAt = 1000)
        assertThat(
            FireGate.check(state, "k1", nowMillis = 1500, cooldownMs = 500, dailyLimit = 3, zone = zone),
        ).isEqualTo(FireGate.Verdict.Pass)
    }

    @Test
    fun blocksAtDailyLimit() {
        val state = empty.copy(dayKey = FireGate.dayKey(0, zone), firedToday = 3)
        val verdict = FireGate.check(state, "k1", nowMillis = 0, cooldownMs = 0, dailyLimit = 3, zone = zone)
        assertThat(verdict).isEqualTo(FireGate.Verdict.Blocked(FireGate.Verdict.Reason.DAILY_LIMIT))
    }

    @Test
    fun dailyCounterResetsOnNewDay() {
        val state = empty.copy(dayKey = "2000-01-01", firedToday = 3)
        assertThat(
            FireGate.check(state, "k1", nowMillis = 0, cooldownMs = 0, dailyLimit = 3, zone = zone),
        ).isEqualTo(FireGate.Verdict.Pass)
    }

    @Test
    fun blocksDuplicateKey() {
        val state = empty.copy(recentKeys = listOf("k1", "k2"))
        val verdict = FireGate.check(state, "k2", nowMillis = 0, cooldownMs = 0, dailyLimit = 3, zone = zone)
        assertThat(verdict).isEqualTo(FireGate.Verdict.Blocked(FireGate.Verdict.Reason.DUPLICATE))
    }

    @Test
    fun emptyKeyNeverDuplicates() {
        val state = empty.copy(recentKeys = listOf(""))
        assertThat(
            FireGate.check(state, "", nowMillis = 0, cooldownMs = 0, dailyLimit = 3, zone = zone),
        ).isEqualTo(FireGate.Verdict.Pass)
    }

    @Test
    fun recordUpdatesStateAndTrimsKeys() {
        var state = TriggerState(dayKey = FireGate.dayKey(0, zone), firedToday = 1, recentKeys = listOf("a", "b"))
        state = FireGate.record(state, "c", nowMillis = 0, zone = zone, window = 2)
        assertThat(state.firedToday).isEqualTo(2)
        assertThat(state.recentKeys).containsExactly("b", "c").inOrder()
        assertThat(state.lastFiredAt).isEqualTo(0)
    }

    @Test
    fun recordResetsCounterOnNewDay() {
        val state = TriggerState(dayKey = "2000-01-01", firedToday = 5)
        val next = FireGate.record(state, "k", nowMillis = 0, zone = zone, window = 10)
        assertThat(next.firedToday).isEqualTo(1)
    }
}
