package dev.aaa1115910.biliapi.http.util

import java.util.Locale

object BiliAppConf {
    /** 仅使用 IPv4：由应用设置写入，[BiliDns] 与 gRPC 通道构建读取。 */
    @Volatile
    var ipv4Only: Boolean = false

    const val GRPC_HOST = "grpc.biliapi.net"
    const val GRPC_PORT = 443
    const val APP_ID = 5
    const val APP_BUILD_CODE = 2020100
    const val APP_VERSION_NAME = "2.2.0"
    const val CHANNEL = "bili"
    const val MOBI_APP = "android_hd"
    const val DEVICE = ""
    const val PLATFORM = "android"
    const val TIMEZONE = "Asia/Shanghai"

    /** UA / 公共参数里的网络档位：wifi=2，移动网络=1，其他=0。 */
    const val NETWORK = 2

    @Volatile
    var currentNetwork: Int = NETWORK

    @Volatile
    var osVersion = "15"

    @Volatile
    var model = "2505DRP06G"

    @Volatile
    var brand = ""

    /** App 内语言；未由应用覆盖时跟随系统。 */
    @Volatile
    var currentLocale: String = defaultLocaleTag()

    /** 系统语言。 */
    @Volatile
    var systemLocale: String = defaultLocaleTag()

    private fun defaultLocaleTag(): String {
        val locale = Locale.getDefault()
        if (locale.language != "zh") return locale.toLanguageTag()
        val script = locale.script.ifBlank {
            if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
        }
        return "zh-$script"
    }
}
