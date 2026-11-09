package dev.aaa1115910.biliapi.repositories

import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliPassportHttpApi
import dev.aaa1115910.biliapi.http.util.AppRequestContext
import dev.aaa1115910.biliapi.http.util.encryptFingerprint
import dev.aaa1115910.biliapi.http.util.toAndroidDeviceInfo
import dev.aaa1115910.biliapi.http.util.toGuestDeviceInfoJson
import org.koin.core.annotation.Single

/**
 * 文档 §5：设备指纹与游客身份。
 *
 * - `fp_local` 由应用层生成并持久化；
 * - `fp_remote` 由 `/x/resource/fingerprint` 返回；
 * - 未登录时尝试 `/x/passport-user/guest/reg` 获取 `GuestId`。
 */
@Single
class FingerprintRepository(
    private val authRepository: AuthRepository
) {
    suspend fun ensureFingerprint(): String? {
        AppRequestContext.fpRemote?.takeIf { it.isNotBlank() }?.let { return it }
        val snapshot = AppRequestContext.deviceInfoProvider() ?: return null
        val body = runCatching { encryptFingerprint(snapshot.toAndroidDeviceInfo()) }
            .getOrNull() ?: return null
        val response = runCatching {
            BiliHttpApi.reportFingerprint(body, authRepository.accessToken)
        }.getOrNull() ?: return null
        val deviceId = response.data?.biliDeviceId?.takeIf { it.isNotBlank() } ?: return null
        AppRequestContext.fpRemote = deviceId
        AppRequestContext.onFpRemoteChanged(deviceId)
        return deviceId
    }

    suspend fun ensureGuestId(): String? {
        AppRequestContext.guestId?.takeIf { it.isNotBlank() }?.let { return it }
        if (!authRepository.accessToken.isNullOrBlank()) return null
        val snapshot = AppRequestContext.deviceInfoProvider() ?: return null
        val deviceInfo = runCatching { snapshot.toGuestDeviceInfoJson() }.getOrNull() ?: return null
        val response = runCatching {
            BiliPassportHttpApi.guestReg(deviceInfo, authRepository.accessToken)
        }.getOrNull() ?: return null
        val guestId = response.data?.guestId?.takeIf { it.isNotBlank() } ?: return null
        AppRequestContext.guestId = guestId
        AppRequestContext.onGuestIdChanged(guestId)
        return guestId
    }
}
