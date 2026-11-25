package com.example.autop.device.impl.bgdisplay.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDisplayPlacementTest {
    private val virtualApp = task(8, 101, "sample.background")
    private val primaryApp = task(0, 202, "sample.foreground")

    @Test
    fun `a launch that lands on the physical display seals the session`() {
        val guard = BackgroundDisplayPlacement()
        guard.watchPackage("sample.background")
        val escaped = virtualApp.copy(displayId = 0)

        assertFalse(guard.observe(emptyList(), listOf(escaped), null, escaped, false, 0))
        assertFalse(guard.observe(listOf(virtualApp), emptyList(), null, null, false, 100))
    }

    @Test
    fun `a task hidden behind another display task still invalidates its owner`() {
        val guard = BackgroundDisplayPlacement()
        val split = primaryApp.copy(packages = primaryApp.packages + "sample.background")

        assertFalse(guard.observe(listOf(virtualApp), listOf(split), null, split, false, 0))
    }

    @Test
    fun `switching applications on the virtual display is allowed`() {
        val guard = BackgroundDisplayPlacement()
        val next = task(8, 103, "sample.next")

        assertTrue(guard.observe(listOf(virtualApp), listOf(primaryApp), null, primaryApp, false, 0))
        assertTrue(guard.observe(listOf(next), listOf(primaryApp), primaryApp, primaryApp, true, 50))
    }

    @Test
    fun `a physical foreground transition during input seals the session`() {
        val guard = BackgroundDisplayPlacement()
        val next = task(0, 203, "sample.browser")

        assertFalse(guard.observe(listOf(virtualApp), listOf(next), primaryApp, next, true, 50))
    }

    @Test
    fun `an unrelated physical transition between virtual operations is allowed`() {
        val guard = BackgroundDisplayPlacement()
        val next = task(0, 203, "sample.browser")

        assertTrue(guard.observe(listOf(virtualApp), listOf(primaryApp), primaryApp, primaryApp, true, 0))
        assertTrue(guard.observe(listOf(virtualApp), listOf(next), null, next, false, 100))
    }

    private fun task(
        displayId: Int,
        id: Int,
        packageName: String,
    ): BackgroundDisplayPlacement.Task =
        BackgroundDisplayPlacement.Task(displayId, id, "$packageName/.Main", packageName, setOf(packageName), true)
}
