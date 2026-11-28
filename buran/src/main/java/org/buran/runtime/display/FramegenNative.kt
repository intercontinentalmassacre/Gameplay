package org.buran.runtime.display

import android.view.Surface
import java.nio.ByteBuffer

internal object FramegenNative {
    init {
        System.loadLibrary("buran_framegen")
    }

    external fun open(surface: Surface, shaders: String, width: Int, height: Int): Long
    external fun enableDiagnostics(handle: Long)
    // Negative means failure, zero means an unchanged frame, and positive means a frame was copied.
    external fun stage(handle: Long, rgba: ByteBuffer, rowStride: Int, pixelStride: Int): Int
    // Returns the number of generated frames presented, or a negative result on failure.
    external fun present(handle: Long, timestampNanos: Long): Int
    external fun close(handle: Long)
}
