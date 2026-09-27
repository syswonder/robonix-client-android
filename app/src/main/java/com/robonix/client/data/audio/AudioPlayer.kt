package com.robonix.client.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor() {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    /**
     * Play PCM data on the speaker. Safe to call from any thread — previous
     * playback is stopped and the AudioTrack released under the same lock
     * that the playback-monitor thread uses, avoiding use-after-release
     * crashes.
     *
     * Uses MODE_STREAM so there is no static-buffer size limit; the buffer
     * is written once and the track plays to completion.
     */
    fun play(pcmData: ByteArray) {
        // Stop any in-flight playback, release the old track under lock
        stop()

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuffer, pcmData.size)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }

        synchronized(this) {
            audioTrack = track
            isPlaying = true
        }

        track.write(pcmData, 0, pcmData.size)
        track.play()

        // Monitor thread: release the track once playback finishes or is stopped.
        // All track access is guarded by synchronized(this) to match stop().
        Thread {
            var keepRunning = true
            try {
                while (keepRunning) {
                    val t: AudioTrack?
                    val playing: Boolean
                    synchronized(this) {
                        t = audioTrack
                        playing = isPlaying
                    }
                    if (t == null) { keepRunning = false; break }
                    if (!playing) { keepRunning = false; break }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) { keepRunning = false; break }
                    Thread.sleep(50)
                }
            } finally {
                synchronized(this) {
                    if (audioTrack === track) {
                        try { track.stop() } catch (_: Exception) {}
                        track.release()
                        audioTrack = null
                        isPlaying = false
                    }
                }
            }
        }.start()
    }

    fun stop() {
        synchronized(this) {
            isPlaying = false
            val t = audioTrack
            audioTrack = null
            if (t != null) {
                try {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
            }
        }
    }

    // ── streaming playback (reverse bridge TTS) ────────────────────────────
    //
    // The robot pushes one TTS utterance as many small PCM frames, so each
    // frame must be appended to a single long-lived AudioTrack instead of
    // restarting playback per frame (which is what play() would do).

    private val streamLock = Any()
    private var streamTrack: AudioTrack? = null

    /** Open the streaming track; no-op when one is already open. */
    fun beginStream() {
        synchronized(streamLock) {
            if (streamTrack != null) return
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 8000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }
            track.play()
            streamTrack = track
        }
    }

    /** Append one PCM frame; blocks when the track buffer is full (backpressure). */
    fun writeStream(pcm: ByteArray) {
        val track = synchronized(streamLock) { streamTrack } ?: return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
            stopStream()
        }
    }

    /** Finish the utterance: play out what is buffered, then release. */
    fun endStream() {
        val track = synchronized(streamLock) {
            streamTrack.also { streamTrack = null }
        } ?: return
        Thread {
            try {
                // MODE_STREAM stop() plays the last written buffer first.
                track.stop()
            } catch (_: Exception) {}
            var waited = 0
            while (waited < 500) {
                val state = try {
                    track.playState
                } catch (_: Exception) {
                    AudioTrack.PLAYSTATE_STOPPED
                }
                if (state != AudioTrack.PLAYSTATE_PLAYING) break
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                waited += 1
            }
            try { track.release() } catch (_: Exception) {}
        }.start()
    }

    /** Abort the utterance now (voice barge-in / disconnect). */
    fun stopStream() {
        val track = synchronized(streamLock) {
            streamTrack.also { streamTrack = null }
        } ?: return
        try { track.pause() } catch (_: Exception) {}
        try { track.flush() } catch (_: Exception) {}
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }
}
