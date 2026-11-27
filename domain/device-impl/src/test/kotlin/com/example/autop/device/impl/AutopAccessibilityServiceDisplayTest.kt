package com.example.autop.device.impl

import android.view.Display
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutopAccessibilityServiceDisplayTest {
    @Test
    fun `only physical display events enter the shared real-screen bus`() {
        assertThat(isMainDisplayEvent(Display.DEFAULT_DISPLAY)).isTrue()
        assertThat(isMainDisplayEvent(Display.DEFAULT_DISPLAY + 7)).isFalse()
    }
}
