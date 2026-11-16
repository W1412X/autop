package com.example.autop.device.impl.shell.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellHostProcessTest {
    @Test
    fun `parses a stat line whose command name contains spaces and parentheses`() {
        val line = "4321 (sh -c (echo) x) S 4000 4321 4321 0 -1 4194304 1 0"
        val stat = ShellHostProcess.parseStat(line)
        assertThat(stat).isEqualTo(ProcStat(pid = 4321L, ppid = 4000L, pgrp = 4321L, session = 4321L))
    }

    @Test
    fun `returns null for malformed stat text`() {
        assertThat(ShellHostProcess.parseStat("garbage")).isNull()
        assertThat(ShellHostProcess.parseStat("12 (x) S")).isNull()
    }

    @Test
    fun `parses the process state character`() {
        val stat = ShellHostProcess.parseStat("77 (sleep) Z 50 77 77 0 -1 4194304 1 0")
        assertThat(stat?.state).isEqualTo('Z')
    }

    @Test
    fun `zombie processes do not count as job members`() {
        val stats =
            listOf(
                ProcStat(pid = 500L, ppid = 1L, pgrp = 500L, session = 500L, state = 'S'),
                ProcStat(pid = 501L, ppid = 500L, pgrp = 500L, session = 500L, state = 'Z'),
            )
        val selected = ShellHostProcess.selectJobProcesses(500L, stats, self = 1L)
        assertThat(selected).containsExactly(500L)
    }

    @Test
    fun `selects the process group leader its members and their descendants`() {
        val job = 1000L
        val stats =
            listOf(
                ProcStat(pid = 1000L, ppid = 900L, pgrp = 1000L, session = 1000L), // leader
                ProcStat(pid = 1001L, ppid = 1000L, pgrp = 1000L, session = 1000L), // group member
                ProcStat(pid = 1002L, ppid = 1001L, pgrp = 1002L, session = 1000L), // session member, own group
                ProcStat(pid = 1003L, ppid = 1002L, pgrp = 1003L, session = 1003L), // descendant re-grouped
                ProcStat(pid = 2000L, ppid = 1L, pgrp = 2000L, session = 2000L), // unrelated
            )
        val selected = ShellHostProcess.selectJobProcesses(job, stats, self = 9999L)
        assertThat(selected).containsExactly(1000L, 1001L, 1002L, 1003L)
    }

    @Test
    fun `never selects the host itself or system processes`() {
        val job = 50L
        val stats =
            listOf(
                ProcStat(pid = 50L, ppid = 1L, pgrp = 50L, session = 50L),
                ProcStat(pid = 1L, ppid = 0L, pgrp = 50L, session = 50L),
                ProcStat(pid = 77L, ppid = 50L, pgrp = 50L, session = 50L),
            )
        val selected = ShellHostProcess.selectJobProcesses(job, stats, self = 77L)
        assertThat(selected).containsExactly(50L)
    }

    @Test
    fun `orphaned descendant is found through its parent chain even after leaving the group`() {
        val job = 300L
        val stats =
            listOf(
                ProcStat(pid = 300L, ppid = 1L, pgrp = 300L, session = 300L),
                ProcStat(pid = 301L, ppid = 300L, pgrp = 999L, session = 999L),
                ProcStat(pid = 302L, ppid = 301L, pgrp = 999L, session = 999L),
            )
        val selected = ShellHostProcess.selectJobProcesses(job, stats, self = 1L)
        assertThat(selected).containsExactly(300L, 301L, 302L)
    }
}
