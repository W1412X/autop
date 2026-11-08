package com.example.autop.feature.overlay.ball

import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

class BallStateHolderTest {
    private val store = FakePositionStore()

    @Test
    fun `placeDefault puts ball on right edge upper third`() {
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        assertThat(holder.position.value)
            .isEqualTo(BallPosition(900, 2000 / 3, side = BallSide.RIGHT, dock = BallDock.NONE))
    }

    @Test
    fun `placeDefault keeps persisted position`() {
        store.saved = BallPosition(10, 20)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        assertThat(holder.position.value).isEqualTo(BallPosition(10, 20))
    }

    @Test
    fun `placeDefault clamps persisted position back inside rotated screen`() {
        // N192：旋转后旧坐标越界（950 > 1000-100、2500 > 2000-100）必须钳回屏内。
        store.saved = BallPosition(950, 2500)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        assertThat(holder.position.value).isEqualTo(BallPosition(900, 1900))
    }

    @Test
    fun `placeDefault re-anchors docked ball to same side after rotation`() {
        // 旋转（1080→2400）后按停靠锚在新屏宽重摆：细条 x=2400-4、半贴边 x=2400-35（露出 0.35×100）。
        store.saved = BallPosition(1052, 300, side = BallSide.RIGHT, dock = BallDock.SLIVER)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 2400, screenHeightPx = 1080, ballSizePx = 100, sliverPx = 4)
        assertThat(holder.position.value)
            .isEqualTo(BallPosition(2396, 300, side = BallSide.RIGHT, dock = BallDock.SLIVER))

        store.saved = BallPosition(-65, 300, side = BallSide.LEFT, dock = BallDock.EDGE)
        val leftHolder = newHolder()
        leftHolder.placeDefault(screenWidthPx = 2400, screenHeightPx = 1080, ballSizePx = 100, sliverPx = 4)
        assertThat(leftHolder.position.value)
            .isEqualTo(BallPosition(-65, 300, side = BallSide.LEFT, dock = BallDock.EDGE))
    }

    @Test
    fun `onDragEnd near edge docks and persists`() {
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        holder.onMoved(x = 920, y = 300)
        holder.onDragEnd(ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4, magnetPx = 40)
        val docked = BallPosition(965, 300, side = BallSide.RIGHT, dock = BallDock.EDGE)
        assertThat(holder.position.value).isEqualTo(docked)
        assertThat(store.saved).isEqualTo(docked)
    }

    @Test
    fun `onDragEnd in middle stays free and persists`() {
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        holder.onMoved(x = 400, y = 300)
        holder.onDragEnd(ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4, magnetPx = 40)
        val free = BallPosition(400, 300, side = BallSide.NONE, dock = BallDock.NONE)
        assertThat(holder.position.value).isEqualTo(free)
        assertThat(store.saved).isEqualTo(free)
    }

    @Test
    fun `onReveal brings docked ball on screen and persists`() {
        store.saved = BallPosition(996, 300, side = BallSide.RIGHT, dock = BallDock.SLIVER)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100, sliverPx = 4)
        holder.onReveal(screenWidthPx = 1000, ballSizePx = 100)
        val revealed = BallPosition(900, 300, side = BallSide.RIGHT, dock = BallDock.NONE)
        assertThat(holder.position.value).isEqualTo(revealed)
        assertThat(store.saved).isEqualTo(revealed)
    }

    @Test
    fun `onDragEnd before placement is ignored`() {
        val holder = newHolder()
        holder.onDragEnd(ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4, magnetPx = 40)
        assertThat(holder.position.value).isEqualTo(BallPosition(BallStateHolder.UNSET, BallStateHolder.UNSET))
        assertThat(store.saved).isNull()
    }

    private class FakeRecordingSession : com.example.autop.flow.RecordingSession {
        override val state: kotlinx.coroutines.flow.StateFlow<com.example.autop.flow.RecordingState> =
            kotlinx.coroutines.flow.MutableStateFlow(
                com.example.autop.flow
                    .RecordingState(),
            )

        override fun start() = Unit

        override fun undo() = Unit

        override suspend fun finish(
            name: String,
            paramNames: Map<String, String>,
        ): com.example.autop.core.common.Outcome<String> =
            com.example.autop.core.common.Outcome
                .Ok("")

        override fun cancel() = Unit

        override fun typedInputs(): List<com.example.autop.flow.TypedInput> = emptyList()

        override suspend fun polishName(
            name: String,
        ): com.example.autop.core.common.Outcome<com.example.autop.flow.PolishedName> =
            com.example.autop.core.common.Outcome.Err(
                com.example.autop.core.common
                    .AppError(code = "test", message = "unused"),
            )

        override suspend fun trialRun(
            scope: com.example.autop.flow.TrialScope,
        ): com.example.autop.core.common.Outcome<com.example.autop.agent.RunOutcome> =
            com.example.autop.core.common.Outcome.Err(
                com.example.autop.core.common
                    .AppError(code = "test", message = "unused"),
            )

        override fun addPicked(
            element: com.example.autop.device.PickedElement,
            kind: com.example.autop.flow.PickKind,
        ) = Unit
    }

    private fun newHolder(): BallStateHolder =
        BallStateHolder(
            runService = FakeRunService(),
            recording = FakeRecordingSession(),
            accessibility = FakeAccessibilityStatus(),
            configProvider = FakeConfigProvider(),
            positionStore = store,
            logger = NoopLogger,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
        )

    private class FakePositionStore : BallPositionStore {
        var saved: BallPosition? = null

        override fun load(): BallPosition? = saved

        override fun save(position: BallPosition) {
            saved = position
        }
    }
}
