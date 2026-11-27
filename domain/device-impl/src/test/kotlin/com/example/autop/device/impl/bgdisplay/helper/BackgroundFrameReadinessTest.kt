package com.example.autop.device.impl.bgdisplay.helper

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackgroundFrameReadinessTest {
    @Test
    fun `capture timing matches DSH reference`() {
        assertThat(BackgroundFrameReadiness.CAPTURE_TIMEOUT_MS).isEqualTo(2_500L)
        assertThat(BackgroundFrameReadiness.RETRY_WAIT_MS).isEqualTo(200L)
    }

    @Test
    fun `only a fresh non-black frame is ready`() {
        assertThat(BackgroundFrameReadiness.next(black = false, attempt = 0, remainingMs = 0))
            .isEqualTo(BackgroundFrameReadiness.Next.READY)
        assertThat(BackgroundFrameReadiness.next(black = false, attempt = 1, remainingMs = 0))
            .isEqualTo(BackgroundFrameReadiness.Next.READY)
    }

    @Test
    fun `black first frame gets one bounded retry`() {
        assertThat(BackgroundFrameReadiness.next(black = true, attempt = 0, remainingMs = 1))
            .isEqualTo(BackgroundFrameReadiness.Next.RETRY)
        assertThat(BackgroundFrameReadiness.next(black = true, attempt = 0, remainingMs = 0))
            .isEqualTo(BackgroundFrameReadiness.Next.NOT_READY)
        assertThat(BackgroundFrameReadiness.next(black = true, attempt = 1, remainingMs = 2_500))
            .isEqualTo(BackgroundFrameReadiness.Next.NOT_READY)
        assertThat(BackgroundFrameReadiness.retryWaitMs(2_500)).isEqualTo(200L)
        assertThat(BackgroundFrameReadiness.retryWaitMs(75)).isEqualTo(75L)
        assertThat(BackgroundFrameReadiness.retryWaitMs(-1)).isEqualTo(0L)
    }

    @Test
    fun `near black sampling follows reference threshold`() {
        val black = bitmap(240, 480, Color.BLACK)
        val dark = bitmap(240, 480, Color.rgb(11, 11, 11))
        val visible = bitmap(240, 480, Color.WHITE)
        try {
            assertThat(black.isMostlyBlack()).isTrue()
            assertThat(dark.isMostlyBlack()).isTrue()
            assertThat(visible.isMostlyBlack()).isFalse()
        } finally {
            black.recycle()
            dark.recycle()
            visible.recycle()
        }
    }

    private fun bitmap(
        width: Int,
        height: Int,
        color: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
