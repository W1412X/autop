/**
 * 流程版本留存的验收用例（docs/design/flows-precondition-selfrepair.md S2/S5）。
 *
 * 覆盖：保存 / 列表 / 取出 / 回滚，以及并发保存不撞号。
 */
package com.example.autop.flow.impl.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.Clock
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.ToolNode
import com.example.autop.flow.impl.db.FlowsDb
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FlowVersionStoreTest {
    private lateinit var db: FlowsDb
    private lateinit var flows: RoomFlowStore
    private lateinit var versions: RoomFlowVersionStore

    private val clock =
        object : Clock {
            override fun nowMillis(): Long = 1_000L
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, FlowsDb::class.java)
                .allowMainThreadQueries()
                .build()
        val runs =
            RoomFlowRunStore(
                db,
                context.filesDir,
                com.example.autop.core.testing
                    .FakeConfigProvider(),
            )
        flows = RoomFlowStore(db, runs, clock) { false }
        versions = RoomFlowVersionStore(db, clock, flows)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun flow(name: String) =
        FlowDefinition(
            id = "f1",
            name = name,
            steps = listOf(ToolNode(id = "n1", tool = "say")),
        )

    @Test
    fun `saved versions can be listed and fetched`() =
        runBlocking {
            versions.save("f1", flow("第一版"), reason = "改版前")
            versions.save("f1", flow("第二版"), reason = "改版后")
            val list = versions.list("f1")
            assertThat(list.map { it.version }).containsExactly(2, 1).inOrder()
            assertThat(versions.get("f1", 1)?.name).isEqualTo("第一版")
            assertThat(versions.get("f1", 9)).isNull()
        }

    @Test
    fun `overwriting a flow archives the previous definition`() =
        runBlocking {
            flows.save(flow("第一版"))
            flows.save(flow("第二版"))
            val archived = versions.list("f1")
            assertThat(archived).hasSize(1)
            assertThat(archived.first().definition.name).isEqualTo("第一版")
        }

    @Test
    fun `restoring a version writes it back and archives the current definition`() =
        runBlocking {
            flows.save(flow("第一版"))
            flows.save(flow("第二版"))
            val restored = versions.restore("f1", 1)
            assertThat(restored).isNotNull()
            assertThat(
                flows
                    .get(
                        com.example.autop.core.common
                            .FlowId("f1"),
                    )?.definition
                    ?.name,
            ).isEqualTo("第一版")
            // 回滚本身也留痕：当前定义进版本库。
            assertThat(versions.list("f1").map { it.definition.name }).contains("第二版")
        }

    @Test
    fun `restoring an unknown version fails`() =
        runBlocking {
            val result = versions.restore("f1", 7)
            assertThat(result).isInstanceOf(com.example.autop.core.common.Outcome.Err::class.java)
            val err = result as com.example.autop.core.common.Outcome.Err
            assertThat(err.error.code).isEqualTo(FlowErrorCodes.NOT_FOUND)
        }

    @Test
    fun `concurrent saves do not clash on the version number`() {
        runBlocking {
            val jobs =
                (1..4).map { i ->
                    async {
                        versions.save("f1", flow("第${i}版"), reason = "并发$i")
                    }
                }
            jobs.awaitAll()
            val list = versions.list("f1")
            assertThat(list.map { it.version }).containsExactly(1, 2, 3, 4)
            assertThat(list.map { it.definition.name }).containsExactly("第1版", "第2版", "第3版", "第4版")
        }
    }
}
