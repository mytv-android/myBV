package dev.aaa1115910.biliapi.http.entity.login

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenRefreshData(
    @SerialName("token_info")
    val tokenInfo: TokenInfo? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val mid: Long? = null,
    @SerialName("expires_in")
    val expiresIn: Int? = null,
) {
    @Serializable
    data class TokenInfo(
        val mid: Long? = null,
        @SerialName("expires_in")
        val expiresIn: Int? = null,
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
    )
}

@Serializable
data class GuestData(
    @SerialName("guest_id")
    val guestId: String? = null,
    @SerialName("access_key")
    val accessKey: String? = null,
    val mid: Long? = null,
)
