package com.example.autop.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class BackgroundRunBlockTest {
    @Test
    public fun `available only with supported sdk and connected channel`() {
        assertNull(BackgroundRunBlock.of(supported = true, channelConnected = true, running = false))
    }

    @Test
    public fun `running blocker has highest priority`() {
        assertEquals(
            BackgroundRunBlock.RUNNING,
            BackgroundRunBlock.of(supported = false, channelConnected = false, running = true),
        )
    }

    @Test
    public fun `sdk blocker precedes disconnected channel`() {
        assertEquals(
            BackgroundRunBlock.UNSUPPORTED,
            BackgroundRunBlock.of(supported = false, channelConnected = false, running = false),
        )
        assertEquals(
            BackgroundRunBlock.CHANNEL_DISCONNECTED,
            BackgroundRunBlock.of(supported = true, channelConnected = false, running = false),
        )
    }

    @Test
    public fun `an unavailable display can be turned off even after restore to an unsupported device`() {
        assertTrue(BackgroundRunBlock.canToggle(BackgroundRunBlock.CHANNEL_DISCONNECTED, checked = true))
        assertTrue(BackgroundRunBlock.canToggle(BackgroundRunBlock.UNSUPPORTED, checked = true))
        assertFalse(BackgroundRunBlock.canToggle(BackgroundRunBlock.CHANNEL_DISCONNECTED, checked = false))
        assertFalse(BackgroundRunBlock.canToggle(BackgroundRunBlock.RUNNING, checked = true))
    }
}
