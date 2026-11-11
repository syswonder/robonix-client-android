package com.robonix.client.data.model

data class ClientSettings(
    val robotHost: String = "",
    val atlasPort: Int = 50051,
    val liaisonEndpoint: String = "",
    val userId: String = "",
    val sessionId: String = "",
    val recordSeconds: Int = 30,
    val language: String = "system",
    val micNodeId: String = "",
    val micDeviceId: String = "",
    val speakerNodeId: String = "",
    val speakerDeviceId: String = "",
    val ttsNodeId: String = "",
    val asrNodeId: String = "",
    val voiceprintNodeId: String = "",
) {
    val cleanHost: String
        get() = robotHost.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore(":")
            .trimEnd('/')

    val atlasEndpoint: String
        get() = if (cleanHost.isNotBlank()) "$cleanHost:$atlasPort" else ""

    val isConfigured: Boolean
        get() = cleanHost.isNotBlank() && atlasPort > 0
}
