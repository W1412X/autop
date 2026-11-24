package com.example.autop.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class BackgroundRunBlockTest {
    @Test
    public fun `available only with supported sdk and connected channel`() {
        assertNull(BackgroundRunBlock.of(supported = true, channelConnected = true, running = false, enabled = true))
    }

    @Test
    public fun `running blocker has highest priority`() {
        assertEquals(
            BackgroundRunBlock.RUNNING,
            BackgroundRunBlock.of(supported = false, channelConnected = false, running = true, enabled = true),
        )
    }

    @Test
    public fun `sdk blocker precedes disconnected channel`() {
        assertEquals(
            BackgroundRunBlock.UNSUPPORTED,
            BackgroundRunBlock.of(supported = false, channelConnected = false, running = false, enabled = true),
        )
        assertEquals(
            BackgroundRunBlock.CHANNEL_DISCONNECTED,
            BackgroundRunBlock.of(supported = true, channelConnected = false, running = false, enabled = false),
        )
    }

    @Test
    public fun `enabled mode can be turned off while channel is disconnected`() {
        assertEquals(
            BackgroundRunBlock.CHANNEL_DISCONNECTED,
            BackgroundRunBlock.of(supported = true, channelConnected = false, running = false, enabled = false),
        )
        assertNull(BackgroundRunBlock.of(supported = true, channelConnected = false, running = false, enabled = true))
        assertEquals(
            BackgroundRunBlock.RUNNING,
            BackgroundRunBlock.of(supported = true, channelConnected = false, running = true, enabled = true),
        )
    }
}
