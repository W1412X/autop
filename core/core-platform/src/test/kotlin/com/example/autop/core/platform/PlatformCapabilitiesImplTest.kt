package com.example.autop.core.platform

import com.example.autop.core.common.Capability
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformCapabilitiesImplTest {
    @Test
    fun `screenshot and ime action are available from sdk 30`() {
        assertThat(PlatformCapabilitiesImpl(29).has(Capability.ACCESSIBILITY_SCREENSHOT)).isFalse()
        assertThat(PlatformCapabilitiesImpl(29).has(Capability.IME_ENTER_ACTION)).isFalse()
        assertThat(PlatformCapabilitiesImpl(30).has(Capability.ACCESSIBILITY_SCREENSHOT)).isTrue()
        assertThat(PlatformCapabilitiesImpl(30).has(Capability.IME_ENTER_ACTION)).isTrue()
    }

    @Test
    fun `notification runtime permission needs sdk 33`() {
        assertThat(PlatformCapabilitiesImpl(32).has(Capability.NOTIFICATION_RUNTIME_PERMISSION)).isFalse()
        assertThat(PlatformCapabilitiesImpl(33).has(Capability.NOTIFICATION_RUNTIME_PERMISSION)).isTrue()
    }

    @Test
    fun `exact alarm grant applies only to sdk 31 and 32`() {
        assertThat(PlatformCapabilitiesImpl(30).has(Capability.EXACT_ALARM_NEEDS_GRANT)).isFalse()
        assertThat(PlatformCapabilitiesImpl(31).has(Capability.EXACT_ALARM_NEEDS_GRANT)).isTrue()
        assertThat(PlatformCapabilitiesImpl(32).has(Capability.EXACT_ALARM_NEEDS_GRANT)).isTrue()
        assertThat(PlatformCapabilitiesImpl(33).has(Capability.EXACT_ALARM_NEEDS_GRANT)).isFalse()
    }

    @Test
    fun `full screen intent grant needs sdk 34`() {
        assertThat(PlatformCapabilitiesImpl(33).has(Capability.FULL_SCREEN_INTENT_NEEDS_GRANT)).isFalse()
        assertThat(PlatformCapabilitiesImpl(34).has(Capability.FULL_SCREEN_INTENT_NEEDS_GRANT)).isTrue()
    }

    @Test
    fun `notification auth required action needs sdk 31`() {
        assertThat(PlatformCapabilitiesImpl(30).has(Capability.NOTIFICATION_AUTH_REQUIRED_ACTION)).isFalse()
        assertThat(PlatformCapabilitiesImpl(31).has(Capability.NOTIFICATION_AUTH_REQUIRED_ACTION)).isTrue()
    }

    @Test
    fun `restricted settings and accessibility data sensitive need sdk 33 and 34`() {
        assertThat(PlatformCapabilitiesImpl(32).has(Capability.RESTRICTED_SETTINGS)).isFalse()
        assertThat(PlatformCapabilitiesImpl(33).has(Capability.RESTRICTED_SETTINGS)).isTrue()
        assertThat(PlatformCapabilitiesImpl(33).has(Capability.ACCESSIBILITY_DATA_SENSITIVE)).isFalse()
        assertThat(PlatformCapabilitiesImpl(34).has(Capability.ACCESSIBILITY_DATA_SENSITIVE)).isTrue()
    }

    @Test
    fun `dynamic color needs sdk 31`() {
        assertThat(PlatformCapabilitiesImpl(30).has(Capability.DYNAMIC_COLOR)).isFalse()
        assertThat(PlatformCapabilitiesImpl(31).has(Capability.DYNAMIC_COLOR)).isTrue()
    }

    @Test
    fun `sdk int is exposed for device quirks matching`() {
        assertThat(PlatformCapabilitiesImpl(31).sdkInt).isEqualTo(31)
    }
}
