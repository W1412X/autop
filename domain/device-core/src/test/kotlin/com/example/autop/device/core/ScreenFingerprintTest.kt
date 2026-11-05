/**
 * 指纹单测（docs/tech/05 §3.3）。
 */
package com.example.autop.device.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenFingerprintTest {
    private fun row(label: String) =
        com.example.autop.device.ScreenNode(
            tag = "btn",
            text = label,
            bounds =
                com.example.autop.device
                    .Rect(0, 0, 100, 50),
        )

    @Test
    fun fingerprintIsTwelveHexChars() {
        val fp = ScreenFingerprint.of("com.example.app", "MainActivity", listOf(row("A")))
        assertThat(fp).hasLength(12)
        assertThat(fp).matches("[0-9a-f]{12}")
    }

    @Test
    fun sameContentGivesSameFingerprint() {
        val a = ScreenFingerprint.of("pkg", "Act", listOf(row("A"), row("B")))
        val b = ScreenFingerprint.of("pkg", "Act", listOf(row("A"), row("B")))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun labelChangeChangesFingerprint() {
        val a = ScreenFingerprint.of("pkg", "Act", listOf(row("旧")))
        val b = ScreenFingerprint.of("pkg", "Act", listOf(row("新")))
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun packageOrActivityChangeChangesFingerprint() {
        val rows = listOf(row("A"))
        val base = ScreenFingerprint.of("pkg", "Act", rows)
        assertThat(ScreenFingerprint.of("other", "Act", rows)).isNotEqualTo(base)
        assertThat(ScreenFingerprint.of("pkg", "Other", rows)).isNotEqualTo(base)
    }
}
