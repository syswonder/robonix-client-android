package com.robonix.client.domain

import com.robonix.client.data.model.AudioDevice
import com.robonix.client.data.model.AudioDeviceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun emptyCaptureIsEmpty() {
        assertEquals(CaptureVerdict.Empty, judgeCapture(pcm16Stats(ByteArray(0))))
    }

    @Test
    fun singleStrayByteHasNoSamples() {
        val stats = pcm16Stats(byteArrayOf(5))
        assertEquals(0, stats.samples)
        assertEquals(CaptureVerdict.Empty, judgeCapture(stats))
    }

    @Test
    fun allZeroSamplesAreSilent() {
        assertEquals(CaptureVerdict.Silent, judgeCapture(pcm16Stats(pcm(0, 0, 0, 0))))
    }

    @Test
    fun peakJustBelowThresholdIsNearSilence() {
        val stats = pcm16Stats(pcm(0, NEAR_SILENCE_PEAK - 1, 0))
        assertEquals(NEAR_SILENCE_PEAK - 1, stats.peak)
        assertEquals(CaptureVerdict.NearSilence, judgeCapture(stats))
    }

    @Test
    fun peakAtThresholdIsOk() {
        assertEquals(CaptureVerdict.Ok, judgeCapture(pcm16Stats(pcm(0, NEAR_SILENCE_PEAK, 0))))
    }

    @Test
    fun negativeSamplesCountByMagnitude() {
        val stats = pcm16Stats(pcm(-640, 100))
        assertEquals(640, stats.peak)
    }

    @Test
    fun mostNegativeSampleDoesNotOverflow() {
        assertEquals(32768, pcm16Stats(pcm(-32768)).peak)
    }

    @Test
    fun rmsOfConstantSignal() {
        val stats = pcm16Stats(pcm(16384, 16384, 16384, 16384))
        assertEquals(0.5, stats.rms, 1e-9)
    }

    @Test
    fun peakDbfsMatchesObservedMic() {
        assertEquals(-34.2, peakDbfs(640)!!, 1e-9)
    }

    @Test
    fun fullScaleIsZeroDb() {
        assertEquals(0.0, peakDbfs(32768)!!, 1e-9)
    }

    @Test
    fun exactSilenceHasNoDbfs() {
        assertNull(peakDbfs(0))
    }

    private val devices = AudioDeviceList(
        devices = listOf(
            AudioDevice(id = "hw:1", name = "USB mic", kind = "input"),
            AudioDevice(id = "hw:2", name = "", kind = "output"),
        ),
        currentInputId = "hw:1",
        currentOutputId = "hw:2",
    )

    @Test
    fun activeInputUsesCurrentInputId() {
        assertEquals(ActiveDevice("hw:1", "USB mic"), activeDeviceFrom(devices, "input"))
    }

    @Test
    fun activeOutputWithBlankNameFallsBackToId() {
        assertEquals(ActiveDevice("hw:2", "hw:2"), activeDeviceFrom(devices, "output"))
    }

    @Test
    fun blankCurrentIdMeansOsDefault() {
        val active = activeDeviceFrom(devices.copy(currentInputId = ""), "input")
        assertEquals("", active.id)
        assertEquals("", active.name)
    }

    @Test
    fun currentIdMissingFromListKeepsTheId() {
        assertEquals("hw:9", activeDeviceFrom(devices.copy(currentInputId = "hw:9"), "input").name)
    }

    @Test
    fun differentSelectionIsPending() {
        assertTrue(selectionPending(selected = "hw:2", current = "hw:1", deviceCount = 2))
    }

    @Test
    fun sameSelectionIsNotPending() {
        assertFalse(selectionPending(selected = "hw:1", current = "hw:1", deviceCount = 2))
    }

    @Test
    fun blankSelectionIsNotPending() {
        assertFalse(selectionPending(selected = "", current = "hw:1", deviceCount = 2))
    }

    @Test
    fun noDeviceListMeansNothingToCompare() {
        assertFalse(selectionPending(selected = "hw:2", current = "", deviceCount = 0))
    }

    @Test
    fun speakerChunksSplitAtChunkSizeInOrder() {
        val audio = ByteArray(70000) { it.toByte() }
        val chunks = speakerChunks(audio, sampleRateHz = 16000)
        assertEquals(listOf(32000, 32000, 6000), chunks.map { it.data.size() })
        assertEquals(listOf(0, 1, 2), chunks.map { it.sequence })
        assertEquals(1.0f, chunks.first().durationS, 1e-6f)
        assertEquals(0.1875f, chunks.last().durationS, 1e-6f)
        assertEquals(audio.toList(), chunks.flatMap { it.data.toByteArray().toList() })
    }

    @Test
    fun emptyAudioHasNoSpeakerChunks() {
        assertTrue(speakerChunks(ByteArray(0), sampleRateHz = 16000).isEmpty())
    }
}
