package org.buran.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

internal class GameAudio(private val endpoint: File, private val audible: () -> Boolean) : Closeable {
    @Volatile private var closed = false
    private val socket = LocalSocket()
    private val playback = thread(name = "Buran game audio", start = false, block = ::play)

    init {
        playback.start()
    }

    private fun play() {
        try {
            connect()
            if (closed) return
            val minimumBuffer = AudioTrack.getMinBufferSize(RATE, CHANNELS, FORMAT)
            check(minimumBuffer > 0) { "Unsupported game audio format ($minimumBuffer)" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(CHANNELS).setEncoding(FORMAT).build())
                .setBufferSizeInBytes(maxOf(minimumBuffer, CHUNK_BYTES * 2))
                .build()
            try {
                val input = DataInputStream(socket.inputStream)
                val pcm = ByteArray(CHUNK_BYTES)
                var firstSignalFrame: Long? = null
                var acceptedFrames = 0L
                var loggedSignal = false
                var muted = !audible()
                if (muted) track.setVolume(0f)
                track.play()
                while (!closed) {
                    input.readFully(pcm)
                    val shouldMute = !audible()
                    if (muted != shouldMute) {
                        muted = shouldMute
                        track.setVolume(if (muted) 0f else 1f)
                    }
                    var offset = 0
                    while (offset < pcm.size && !closed) {
                        val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written > 0 || closed) { "AudioTrack write returned $written" }
                        if (written > 0) offset += written
                    }
                    if (!loggedSignal && offset == pcm.size) {
                        if (firstSignalFrame == null && !muted) {
                            val signalByte = pcm.indexOfFirst { it != 0.toByte() }
                            if (signalByte >= 0) firstSignalFrame = acceptedFrames + signalByte / FRAME_BYTES
                        }
                        acceptedFrames += offset / FRAME_BYTES
                        val signalFrame = firstSignalFrame
                        if (muted) {
                            firstSignalFrame = null
                        } else if (signalFrame != null) {
                            val playedFrames = track.playbackHeadPosition.toLong() and 0xffffffffL
                            if (playedFrames > signalFrame) {
                                loggedSignal = true
                                Log.i(TAG, "Played nonzero game PCM (playback head $playedFrames frames)")
                            }
                        }
                    }
                }
            } finally {
                track.release()
            }
        } catch (_: EOFException) {
            // The guest closes the PCM connection at the end of the session.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed) Log.w(TAG, "Guest audio playback failed", error)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun connect() {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        val address = LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM)
        while (!closed) {
            try {
                socket.connect(address)
                return
            } catch (error: IOException) {
                if (SystemClock.elapsedRealtime() >= deadline) throw error
                Thread.sleep(100)
            }
        }
    }

    override fun close() {
        closed = true
        playback.interrupt()
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "BuranGameAudio"
        private const val RATE = 48_000
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = 4 // Stereo signed 16-bit PCM.
        private const val CHUNK_BYTES = 4_096
    }
}
