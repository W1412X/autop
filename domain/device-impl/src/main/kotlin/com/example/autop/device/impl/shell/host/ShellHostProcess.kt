package com.example.autop.device.impl.shell.host

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** `/proc/<pid>/stat` 中用于回收判定的字段；[state] 为进程状态字符（`Z` 表示僵尸，已退出）。 */
internal data class ProcStat(
    val pid: Long,
    val ppid: Long,
    val pgrp: Long,
    val session: Long,
    val state: Char = 'S',
)

/** 进程组与后代进程的查找、信号发送（shell-exec 设计 §5.4）。 */
internal object ShellHostProcess {
    /**
     * 解析 `/proc/<pid>/stat`。格式：`pid (comm) state ppid pgrp session ...`，
     * comm 可能含空格和括号，所以以最后一个 `)` 为界切分。
     */
    fun parseStat(text: String): ProcStat? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val pid = text.substring(0, open).trim().toLongOrNull()
        val fields = text.substring(close + 1).trim().split(' ')
        val ppid = fields.getOrNull(FIELD_PPID)?.toLongOrNull()
        val pgrp = fields.getOrNull(FIELD_PGRP)?.toLongOrNull()
        val session = fields.getOrNull(FIELD_SESSION)?.toLongOrNull()
        val state = fields.getOrNull(FIELD_STATE)?.firstOrNull()
        if (ppid == null || pgrp == null || state == null) return null
        return pid?.let { p -> session?.let { s -> ProcStat(p, ppid, pgrp, s, state) } }
    }

    /**
     * 选出属于某个 job 的进程：进程组或会话 id 等于 [leaderPid]，或其后代。
     * 排除 [self] 与 pid 小于 2 的系统进程。
     */
    fun selectJobProcesses(
        leaderPid: Long,
        stats: List<ProcStat>,
        self: Long,
    ): Set<Long> {
        // 僵尸与已死进程不再占用资源，也不应阻止回收判定。
        val live = stats.filter { it.state != STATE_ZOMBIE && it.state != STATE_DEAD }
        val members = mutableSetOf<Long>()
        for (s in live) {
            if (s.pgrp == leaderPid || s.session == leaderPid) members += s.pid
        }
        var grew = true
        while (grew) {
            grew = false
            for (s in live) {
                if (s.pid !in members && s.ppid in members) {
                    members += s.pid
                    grew = true
                }
            }
        }
        return members.filterTo(mutableSetOf()) { it != self && it >= MIN_USER_PID }
    }

    /** 向进程组发送信号。进程组已不存在时静默返回。 */
    fun signalGroup(
        pgid: Long,
        signal: Int,
    ) {
        if (pgid <= 0L) return
        try {
            Os.kill(-pgid.toInt(), signal)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ESRCH) throw e
        }
    }

    /** host 自身的 pid，回收时必须排除。 */
    val selfPid: Long by lazy {
        File("/proc/self").canonicalFile.name.toLongOrNull() ?: -1L
    }

    /** 进程组里是否还有进程（包括已脱离主进程的后台残留）。 */
    fun hasMembers(pgid: Long): Boolean = selectJobProcesses(pgid, readAllStats(), selfPid).isNotEmpty()

    /** 读取全部进程的 stat（进程可能在读取过程中退出，失败的条目直接跳过）。 */
    fun readAllStats(): List<ProcStat> {
        val dirs = File(PROC_ROOT).listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            // 进程可能在读取过程中退出，读取失败的条目直接跳过。
            runCatching { parseStat(File(dir, STAT_FILE).readText()) }.getOrNull()
        }
    }

    /**
     * 按进程组、会话与父子关系查找并杀掉 job 的残留后代。重复 [rounds] 轮，
     * 以覆盖“杀掉父进程后子进程才被挂到 init 下”的情况。
     */
    fun reapJob(
        pgid: Long,
        self: Long,
        rounds: Int = REAP_ROUNDS,
    ) {
        repeat(rounds) {
            val victims = selectJobProcesses(pgid, readAllStats(), self)
            if (victims.isEmpty()) return
            for (pid in victims) {
                try {
                    Os.kill(pid.toInt(), OsConstants.SIGKILL)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.ESRCH) throw e
                }
            }
        }
    }

    /** `stat` 字段下标（以 `)` 之后的第一个字段 state 为 0）。 */
    private const val FIELD_STATE = 0
    private const val FIELD_PPID = 1
    private const val STATE_ZOMBIE = 'Z'
    private const val STATE_DEAD = 'X'
    private const val FIELD_PGRP = 2
    private const val FIELD_SESSION = 3
    private const val STAT_FILE = "stat"
    private const val MIN_USER_PID = 2L
    private const val REAP_ROUNDS = 3
    private const val PROC_ROOT = "/proc"
}
