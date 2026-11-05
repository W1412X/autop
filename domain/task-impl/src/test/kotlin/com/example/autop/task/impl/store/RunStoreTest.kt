/**
 * [RunStore] 单测（N180）：`mutate` 读-改-写原子性——get-then-update 两步写不原子，
 * ask_user 刚写的 waitingQuestion/waitingOptions 会被 state 回写的旧快照覆盖
 * （对话页 AskCard 显示空白卡片、没有问题和选项）。
 */
package com.example.autop.task.impl.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.db.TasksDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 并发交错轮数（旧 get-then-update 写法下多轮必现字段丢失）。 */
private const val STORE_RACE_ROUNDS: Int = 30

/** ask 写入的问题（并发写后必须完整保留）。 */
private const val STORE_QUESTION: String = "继续吗？"

/** ask 写入的选项。 */
private val STORE_OPTIONS: List<String> = listOf("是", "否")

/** N180：并发读-改-写不丢字段。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunStoreTest {
    private lateinit var db: TasksDb
    private lateinit var runStore: RunStore

    @Before
    public fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TasksDb::class.java)
                .allowMainThreadQueries()
                .build()
        runStore = RunStore(db)
    }

    @After
    public fun tearDown() {
        db.close()
    }

    /**
     * 竞态回归（N180）：state 回写（只改 status）与 ask 写入（status+问题+选项）
     * 并发交错，任一顺序落库都不得丢 waiting 字段。
     */
    @Test
    public fun `concurrent partial updates keep waiting fields`() =
        runTest {
            repeat(STORE_RACE_ROUNDS) { round ->
                val record = recordOf("run-$round")
                runStore.save(record)
                val statusWrite =
                    async(Dispatchers.Default) {
                        runStore.mutate(record.id) { it.copy(status = RunStatus.WAITING_USER) }
                    }
                val askWrite =
                    async(Dispatchers.Default) {
                        runStore.mutate(record.id) {
                            it.copy(
                                status = RunStatus.WAITING_USER,
                                waitingQuestion = STORE_QUESTION,
                                waitingOptions = STORE_OPTIONS,
                            )
                        }
                    }
                statusWrite.await()
                askWrite.await()
                val saved = runStore.get(record.id)
                assertEquals(STORE_QUESTION, saved?.waitingQuestion)
                assertEquals(STORE_OPTIONS, saved?.waitingOptions)
            }
        }

    /** 记录不存在时 mutate 静默跳过（与旧 get?.let 语义一致）。 */
    @Test
    public fun `mutate on missing record is a no-op`() =
        runTest {
            runStore.mutate(RunId("missing")) { it.copy(status = RunStatus.FAILED) }
            assertNull(runStore.get(RunId("missing")))
        }
}

/** 待写记录（初始 RUNNING、无等待字段）。 */
private fun recordOf(id: String): RunRecord =
    RunRecord(
        id = RunId(id),
        taskId = null,
        threadId = ThreadId("thread-$id"),
        origin = RunOrigin.CHAT,
        status = RunStatus.RUNNING,
        triggerContext = emptyMap(),
        createdAt = 10_000L,
        startedAt = 10_000L,
        endedAt = null,
        summary = null,
        errorCode = null,
        pauseReason = null,
        waitingQuestion = null,
    )
