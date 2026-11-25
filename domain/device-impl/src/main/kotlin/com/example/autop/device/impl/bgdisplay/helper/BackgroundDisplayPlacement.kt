package com.example.autop.device.impl.bgdisplay.helper

/** Keeps task identities locally so a background run never receives primary-screen content. */
internal class BackgroundDisplayPlacement {
    data class Task(
        val displayId: Int,
        val id: Int,
        val topActivity: String?,
        val topPackage: String?,
        val packages: Set<String>,
        val visible: Boolean,
    )

    private val watchedPackages = mutableSetOf<String>()
    private var lastWrite: Write? = null
    var failed = false
        private set

    fun watchPackage(packageName: String) {
        watchedPackages += packageName
    }

    fun invalidate() {
        failed = true
    }

    fun observe(
        virtual: List<Task>,
        other: List<Task>,
        primaryBefore: Task?,
        primaryAfter: Task?,
        afterWrite: Boolean,
        now: Long,
    ): Boolean {
        watchedPackages += virtual.filter { it.visible }.mapNotNull { it.topPackage }
        if (afterWrite) {
            lastWrite = Write(primaryBefore, now + REDIRECT_GRACE_MS)
            if (primaryBefore.foregroundChangedTo(primaryAfter)) invalidate()
        }
        val recent = lastWrite?.takeIf { now <= it.expiresAt }
        val virtualForeground = virtual.any { it.visible && it.topActivity != null }
        if (!virtualForeground && recent != null && recent.primary.foregroundChangedTo(primaryAfter)) invalidate()
        if (other.any { task -> task.topActivity != null && task.packages.any(watchedPackages::contains) }) invalidate()
        return !failed
    }

    private data class Write(
        val primary: Task?,
        val expiresAt: Long,
    )

    private fun Task?.foregroundChangedTo(after: Task?): Boolean =
        this?.id != after?.id || this?.topActivity != after?.topActivity

    private companion object {
        const val REDIRECT_GRACE_MS = 2_000L
    }
}
