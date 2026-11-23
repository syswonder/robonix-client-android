package com.robonix.client.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBridgeReplaceTest {
    @Test
    fun automaticReconnectRequiresKnownFreeSlot() {
        assertEquals(AudioBridgePhase.Connecting, reconnectPhase(false))
        assertEquals(AudioBridgePhase.Occupied, reconnectPhase(true))
        assertEquals(AudioBridgePhase.OccupancyUnknown, reconnectPhase(null))
    }

    @Test
    fun matchesCodeAndReason() {
        assertTrue(isReplacedClose(1012, "replaced by newer client"))
    }

    @Test
    fun reasonMatchIgnoresCase() {
        assertTrue(isReplacedClose(1012, "Replaced By Newer Client"))
    }

    @Test
    fun otherCodeIsNotAReplace() {
        assertFalse(isReplacedClose(1001, "replaced by newer client"))
        assertFalse(isReplacedClose(1000, "replaced by newer client"))
    }

    @Test
    fun otherReasonIsNotAReplace() {
        assertFalse(isReplacedClose(1012, "service restart"))
        assertFalse(isReplacedClose(1012, ""))
        assertFalse(isReplacedClose(1012, null))
    }
}
