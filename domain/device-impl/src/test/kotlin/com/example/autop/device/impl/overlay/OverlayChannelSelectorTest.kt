/**
 * 悬浮窗通道选择规则测试（docs/tech/05 §10、ADR-0004）。
 */
package com.example.autop.device.impl.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `auto` 降级、quirk 优先与显式通道的前提校验。 */
public class OverlayChannelSelectorTest {
    @Test
    public fun `auto prefers accessibility when connected`() {
        val channel =
            OverlayChannelSelector.select(
                AUTO,
                null,
                accessibilityConnected = true,
                overlayPermissionGranted = true,
            )
        assertThat(channel).isEqualTo(OverlayChannel.ACCESSIBILITY)
    }

    @Test
    public fun `auto falls back to application with permission`() {
        val channel =
            OverlayChannelSelector.select(
                AUTO,
                null,
                accessibilityConnected = false,
                overlayPermissionGranted = true,
            )
        assertThat(channel).isEqualTo(OverlayChannel.APPLICATION)
    }

    @Test
    public fun `auto returns null when nothing available`() {
        val channel =
            OverlayChannelSelector.select(
                AUTO,
                null,
                accessibilityConnected = false,
                overlayPermissionGranted = false,
            )
        assertThat(channel).isNull()
    }

    @Test
    public fun `quirk overrides auto rule`() {
        val channel =
            OverlayChannelSelector.select(
                AUTO,
                OverlayChannelSelector.TYPE_APPLICATION,
                accessibilityConnected = true,
                overlayPermissionGranted = true,
            )
        assertThat(channel).isEqualTo(OverlayChannel.APPLICATION)
    }

    @Test
    public fun `explicit accessibility needs service connected`() {
        val channel =
            OverlayChannelSelector.select(
                OverlayChannelSelector.TYPE_ACCESSIBILITY,
                null,
                accessibilityConnected = false,
                overlayPermissionGranted = true,
            )
        assertThat(channel).isNull()
    }

    @Test
    public fun `explicit application needs overlay permission`() {
        val channel =
            OverlayChannelSelector.select(
                OverlayChannelSelector.TYPE_APPLICATION,
                null,
                accessibilityConnected = true,
                overlayPermissionGranted = false,
            )
        assertThat(channel).isNull()
    }

    @Test
    public fun `explicit channel used when its precondition holds`() {
        val channel =
            OverlayChannelSelector.select(
                OverlayChannelSelector.TYPE_ACCESSIBILITY,
                OverlayChannelSelector.TYPE_APPLICATION,
                accessibilityConnected = true,
                overlayPermissionGranted = false,
            )
        assertThat(channel).isEqualTo(OverlayChannel.ACCESSIBILITY)
    }

    @Test
    public fun `unknown configured value behaves like auto`() {
        val channel =
            OverlayChannelSelector.select(
                "bogus",
                null,
                accessibilityConnected = true,
                overlayPermissionGranted = true,
            )
        assertThat(channel).isEqualTo(OverlayChannel.ACCESSIBILITY)
    }

    private companion object {
        const val AUTO = OverlayChannelSelector.AUTO
    }
}
