package com.robonix.client.domain

import com.robonix.client.data.model.AudioDevice
import com.robonix.client.data.model.AudioDeviceList
import com.robonix.client.data.model.ClientSettings

sealed class BridgeConnectResult {
    /** The configured mic/speaker is not a reverse bridge, so no client connection is required. */
    object NotNeeded : BridgeConnectResult()

    data class Connected(val url: String) : BridgeConnectResult()

    /** Another client holds the robot's single bridge slot and the caller did not ask to take it over. */
    data class Busy(val providerId: String) : BridgeConnectResult()

    data class Failed(val reason: String) : BridgeConnectResult()
}

internal fun ClientSettings.hasSameAudioRoute(other: ClientSettings): Boolean =
    atlasEndpoint == other.atlasEndpoint && micNodeId == other.micNodeId && speakerNodeId == other.speakerNodeId

/** The bridge provider behind the configured mic or speaker, or null if neither is a bridge. */
internal fun pickBridgeProvider(micNodeId: String, speakerNodeId: String, bridgeIds: Set<String>): String? =
    listOf(micNodeId, speakerNodeId).firstOrNull { it.isNotBlank() && it in bridgeIds }

/** Connect unless another client occupies the slot; an explicit take-over overrides that. */
internal fun mayConnectBridge(occupied: Boolean, heldByUs: Boolean, takeOver: Boolean): Boolean =
    !occupied || heldByUs || takeOver

/**
 * What the bridge answers for list_devices. Android has no per-device routing, and asking the
 * robot would return the devices of whichever client currently holds the slot.
 */
val LOCAL_BRIDGE_DEVICES = AudioDeviceList(
    devices = listOf(
        AudioDevice(id = "0", name = "Android microphone", kind = "input", isDefault = true, channels = 1),
        AudioDevice(id = "1", name = "Android speaker", kind = "output", isDefault = true, channels = 2),
    ),
    currentInputId = "0",
    currentOutputId = "1",
)
