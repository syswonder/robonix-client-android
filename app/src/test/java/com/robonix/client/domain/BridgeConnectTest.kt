package com.robonix.client.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConnectTest {
    private val bridges = setOf("audio_client_bridge")

    @Test
    fun picksBridgeBehindConfiguredMic() {
        assertEquals("audio_client_bridge", pickBridgeProvider("audio_client_bridge", "usb_speaker", bridges))
    }

    @Test
    fun picksBridgeBehindConfiguredSpeaker() {
        assertEquals("audio_client_bridge", pickBridgeProvider("usb_mic", "audio_client_bridge", bridges))
    }

    @Test
    fun ignoresBridgesThatAreNotConfigured() {
        assertNull(pickBridgeProvider("usb_mic", "usb_speaker", bridges))
        assertNull(pickBridgeProvider("", "", bridges))
    }

    @Test
    fun connectsWhenSlotIsFree() {
        assertTrue(mayConnectBridge(occupied = false, heldByUs = false, takeOver = false))
    }

    @Test
    fun refusesWhenAnotherClientHoldsSlot() {
        assertFalse(mayConnectBridge(occupied = true, heldByUs = false, takeOver = false))
    }

    @Test
    fun keepsOwnConnectionWithoutPrompt() {
        assertTrue(mayConnectBridge(occupied = true, heldByUs = true, takeOver = false))
    }

    @Test
    fun explicitTakeOverOverridesOccupied() {
        assertTrue(mayConnectBridge(occupied = true, heldByUs = false, takeOver = true))
    }

    @Test
    fun localDevicesAreOneInputAndOneOutputWithMatchingCurrentIds() {
        val list = LOCAL_BRIDGE_DEVICES
        val input = list.devices.single { it.kind == "input" }
        val output = list.devices.single { it.kind == "output" }
        assertEquals(input.id, list.currentInputId)
        assertEquals(output.id, list.currentOutputId)
    }
}
