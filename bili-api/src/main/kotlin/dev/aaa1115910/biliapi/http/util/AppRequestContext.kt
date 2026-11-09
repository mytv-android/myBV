package dev.aaa1115910.biliapi.http.util

import kotlin.random.Random

/**
 * App 请求（REST 与 gRPC）共用的设备/账号上下文。
 *
 * 由应用层在启动和登录态变化时写入；请求拦截器据此补 `Buvid`、`access_key`、
 * `fp_local`、`fp_remote`、`Display-ID`、`Device-ID`、`session_id`、`GuestId`
 * 与 gRPC 的 `x-bili-*` 元数据。
 */
object AppRequestContext {
    var accessKeyProvider: () -> String? = { null }
    var midProvider: () -> Long? = { null }
    var buvidProvider: () -> String? = { null }

    /** 大屏展示/投屏标识，对应官方 `DisplayID.get()`。 */
    @Volatile
    var displayId: String? = null

    /** 设备 ID，对应官方 `PhoneDeviceId.get()`。 */
    @Volatile
    var deviceId: String? = null

    /** 本地设备指纹，对应官方 `fp_local`。 */
    @Volatile
    var fpLocal: String? = null

    /** `/x/resource/fingerprint` 返回的 `bili_deviceId`，对应官方 `fp_remote`。 */
    @Volatile
    var fpRemote: String? = null

    /** fp_remote 更新回调，由应用层持久化。 */
    var onFpRemoteChanged: (String) -> Unit = {}

    /** 游客 ID，未登录时由 `/x/passport-user/guest/reg` 获取。 */
    @Volatile
    var guestId: String? = null

    /** GuestId 更新回调，由应用层持久化。 */
    var onGuestIdChanged: (String) -> Unit = {}

    /** 首次启动时间戳（毫秒）。 */
    @Volatile
    var firstLaunchTime: Long = 0L

    /** 全局自定义公共参数：statistics、disable_rcmd、teenagers_mode 等。 */
    @Volatile
    var customParams: Map<String, String> = emptyMap()

    @Volatile
    var teenagersMode: Boolean = false

    @Volatile
    var disableRcmd: Boolean = false

    /** 指纹上报所需的设备信息快照，由应用层采集。 */
    var deviceInfoProvider: () -> DeviceInfoSnapshot? = { null }

    /** fawkes 会话 id：8 位 0~9、a~z 字符串，进程内固定。 */
    val sessionId: String = (1..8)
        .map { "0123456789abcdefghijklmnopqrstuvwxyz"[Random.nextInt(36)] }
        .joinToString("")

    /** Foundation 会话 id：4 字节随机数的十六进制，进程内固定。 */
    val foundationSessionId: String = (1..4)
        .joinToString("") { Random.nextInt(256).toString(16).padStart(2, '0') }
}
