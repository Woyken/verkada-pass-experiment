package com.woyken.verkadapass.data

import kotlinx.serialization.Serializable

@Serializable
data class SessionData(
    val userToken: String,
    val organizationId: String,
    val userId: String,
    val email: String,
    val orgShortName: String = "",
    val shardDomain: String = "",
)

@Serializable
data class DoorItem(
    val accessPointId: String,
    val name: String,
    val location: String = "",
)

@Serializable
data class UnlockResult(
    val success: Boolean,
    val duration: Double? = null,
    val error: String? = null,
)
