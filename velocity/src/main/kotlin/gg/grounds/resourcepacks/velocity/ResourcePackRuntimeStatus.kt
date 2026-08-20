package gg.grounds.resourcepacks.velocity

import gg.grounds.resourcepacks.client.PackSetClientStatus

data class ResourcePackRuntimeStatus(
    val clientStatus: PackSetClientStatus,
    val currentFingerprint: String?,
    val fallbackFingerprint: String?,
    val lastError: String?,
    val requested: Long,
    val accepted: Long,
    val downloaded: Long,
    val failed: Long,
    val declined: Long,
)
