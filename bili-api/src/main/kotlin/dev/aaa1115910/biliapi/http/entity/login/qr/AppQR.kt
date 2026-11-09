package dev.aaa1115910.biliapi.http.entity.login.qr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppQRDataRequest(
    val url: String,
    @SerialName("auth_code")
    val authCode: String
)

@Serializable
data class AppQRLoginData(
    @SerialName("is_new")
    val isNew: Boolean = false,
    val mid: Long = 0,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Int = 0,
    @SerialName("token_info")
    val tokenInfo: TokenInfo? = null,
    @SerialName("cookie_info")
    val cookieInfo: CookieInfo,
    val sso: List<String> = emptyList()
) {
    @Serializable
    data class TokenInfo(
        val mid: Long? = null,
        @SerialName("expires_in")
        val expiresIn: Int? = null,
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null
    )

    @Serializable
    data class CookieInfo(
        val cookies: List<Cookie>,
        val domains: List<String>
    ) {
        @Serializable
        data class Cookie(
            var name: String,
            var value: String,
            @SerialName("http_only")
            var httpOnly: Int,
            val expires: Int,
            var secure: Int
        )
    }
}
