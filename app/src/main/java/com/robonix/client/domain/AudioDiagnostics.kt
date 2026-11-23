package com.robonix.client.domain

import com.google.protobuf.ByteString
import com.robonix.client.data.model.AudioDeviceList
import com.robonix.proto.audio.AudioChunk
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** About -60 dBFS: a live mic in a quiet room still exceeds this. Matches the Web client. */
const val NEAR_SILENCE_PEAK = 33

data class PcmStats(val samples: Int, val peak: Int, val rms: Double)

enum class CaptureVerdict { Empty, Silent, NearSilence, Ok }

/** Activity statistics for little-endian signed 16-bit PCM; a trailing odd byte is ignored. */
fun pcm16Stats(pcm: ByteArray): PcmStats {
    var peak = 0
    var sumSq = 0.0
    var samples = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
        peak = maxOf(peak, abs(s))
        sumSq += s.toDouble() * s
        samples++
        i += 2
    }
    val rms = if (samples > 0) sqrt(sumSq / samples) / 32768.0 else 0.0
    return PcmStats(samples, peak, rms)
}

/** Empty and all-zero captures are failures; a nearly flat one is a warning, not a pass. */
fun judgeCapture(stats: PcmStats): CaptureVerdict = when {
    stats.samples == 0 -> CaptureVerdict.Empty
    stats.peak == 0 -> CaptureVerdict.Silent
    stats.peak < NEAR_SILENCE_PEAK -> CaptureVerdict.NearSilence
    else -> CaptureVerdict.Ok
}

/** Peak level of a 16-bit capture in dBFS to one decimal, or null for exact silence. */
fun peakDbfs(peak: Int): Double? =
    if (peak > 0) Math.round(20.0 * log10(peak / 32768.0) * 10.0) / 10.0 else null

/** The device a provider is using right now. A blank [name] means the OS default. */
data class ActiveDevice(val id: String, val name: String)

fun activeDeviceFrom(list: AudioDeviceList, kind: String): ActiveDevice {
    val id = if (kind == "input") list.currentInputId else list.currentOutputId
    val name = list.devices.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id
    return ActiveDevice(id, name)
}

/**
 * True when the dropdown holds a device other than the one in use. With no device list
 * (provider unreachable) the in-use device is unknown, so there is nothing to compare.
 */
fun selectionPending(selected: String, current: String, deviceCount: Int): Boolean =
    deviceCount > 0 && selected.isNotBlank() && selected != current

const val SPEAKER_CHUNK_BYTES = 32000

/** Split one synthesized utterance into the AudioChunk frames the speaker primitive expects. */
fun speakerChunks(audio: ByteArray, sampleRateHz: Int): List<AudioChunk> {
    val bytesPerSecond = (sampleRateHz * 2).toFloat()
    val timestampNs = System.currentTimeMillis() * 1_000_000L
    return (audio.indices step SPEAKER_CHUNK_BYTES).mapIndexed { seq, start ->
        val data = audio.copyOfRange(start, minOf(start + SPEAKER_CHUNK_BYTES, audio.size))
        AudioChunk.newBuilder()
            .setTimestampNs(timestampNs)
            .setData(ByteString.copyFrom(data))
            .setSequence(seq)
            .setDurationS(data.size / bytesPerSecond)
            .build()
    }
}

data class MicTestResult(val bytes: Int, val captureMs: Long, val stats: PcmStats)

data class SpeakerTestResult(val bytes: Int, val sampleRateHz: Int)
