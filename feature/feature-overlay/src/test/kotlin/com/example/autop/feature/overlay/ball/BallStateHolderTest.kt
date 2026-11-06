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
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100)
        assertThat(holder.position.value)
            .isEqualTo(BallPosition(900, 2000 / 3, side = BallSide.RIGHT, hidden = false))
    }

    @Test
    fun `placeDefault keeps persisted position`() {
        store.saved = BallPosition(10, 20)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100)
        assertThat(holder.position.value).isEqualTo(BallPosition(10, 20))
    }

    @Test
    fun `placeDefault clamps persisted position back inside rotated screen`() {
        // N192：旋转后旧坐标越界（950 > 1000-100、2500 > 2000-100）必须钳回屏内。
        store.saved = BallPosition(950, 2500)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100)
        assertThat(holder.position.value).isEqualTo(BallPosition(900, 1900))
    }

    @Test
    fun `placeDefault re-anchors hidden ball to same side after rotation`() {
        // N217：x 绑死保存时的屏宽，旋转（1080→2400）后按贴边锚在新屏宽重摆，
        // 直接钳回会把球放回屏幕中央。
        store.saved = BallPosition(1052, 300, side = BallSide.RIGHT, hidden = true)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 2400, screenHeightPx = 1080, ballSizePx = 100)
        // 默认 edgeHideRatio=0.8 → hide=80 → x = 2400-100+80。
        assertThat(holder.position.value)
            .isEqualTo(BallPosition(2380, 300, side = BallSide.RIGHT, hidden = true))

        store.saved = BallPosition(-80, 300, side = BallSide.LEFT, hidden = true)
        val leftHolder = newHolder()
        leftHolder.placeDefault(screenWidthPx = 2400, screenHeightPx = 1080, ballSizePx = 100)
        assertThat(leftHolder.position.value)
            .isEqualTo(BallPosition(-80, 300, side = BallSide.LEFT, hidden = true))
    }

    @Test
    fun `onSnap persists snapped position`() {
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100)
        holder.onMoved(x = 800, y = 300)
        holder.onSnap(ballSizePx = 100, screenWidthPx = 1000, edgeHideRatio = 0.4)
        assertThat(holder.position.value).isEqualTo(BallPosition(940, 300, side = BallSide.RIGHT, hidden = true))
        assertThat(store.saved).isEqualTo(BallPosition(940, 300, side = BallSide.RIGHT, hidden = true))
    }

    @Test
    fun `onReveal brings hidden ball on screen and persists`() {
        store.saved = BallPosition(980, 300, side = BallSide.RIGHT, hidden = true)
        val holder = newHolder()
        holder.placeDefault(screenWidthPx = 1000, screenHeightPx = 2000, ballSizePx = 100)
        holder.onReveal(screenWidthPx = 1000, ballSizePx = 100)
        assertThat(holder.position.value).isEqualTo(BallPosition(900, 300, side = BallSide.RIGHT, hidden = false))
        assertThat(store.saved).isEqualTo(BallPosition(900, 300, side = BallSide.RIGHT, hidden = false))
    }

    @Test
    fun `onSnap before placement is ignored`() {
        val holder = newHolder()
        holder.onSnap(ballSizePx = 100, screenWidthPx = 1000, edgeHideRatio = 0.4)
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
