package dev.aaa1115910.biliapi.http.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FingerprintData(
    @SerialName("bili_deviceId")
    val biliDeviceId: String? = null,
)
