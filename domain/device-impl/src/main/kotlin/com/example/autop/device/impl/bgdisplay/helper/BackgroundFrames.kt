package com.example.autop.device.impl.bgdisplay.helper

import android.media.Image
import android.media.ImageReader
import android.os.Handler

/** Drain continuously so an idle surface cannot leave the producer stuck behind stale frames. */
internal class BackgroundFrames(
    private val reader: ImageReader,
    handler: Handler,
) : AutoCloseable {
    private val lock = Any()
    private var latest: Image? = null
    private var closed = false

    val surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            synchronized(lock) {
                if (!closed) {
                    source.acquireLatestImage()?.let { fresh ->
                        latest?.close()
                        latest = fresh
                    }
                }
            }
        }, handler)
    }

    fun <T> withLatest(read: (Image) -> T): T? = synchronized(lock) { latest?.let(read) }

    override fun close() {
        synchronized(lock) {
            closed = true
            try {
                latest?.close()
                latest = null
            } finally {
                reader.close()
            }
        }
    }
}
