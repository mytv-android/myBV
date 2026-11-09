package dev.aaa1115910.biliapi.http.plugins

import dev.aaa1115910.biliapi.http.util.BiliAppConf
import dev.aaa1115910.biliapi.http.util.BiliWebConf
import dev.aaa1115910.biliapi.http.util.isMarkedAppRequest
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.host
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.utils.io.KtorDsl

private val LOGGER = KtorSimpleLogger("dev.aaa1115910.biliapi.http.plugins.BiliUserAgent")

@KtorDsl
class BiliUserAgentConfig(
    var version: String = BiliAppConf.APP_VERSION_NAME,
    var buildCode: Int = BiliAppConf.APP_BUILD_CODE,
    var channel: String = BiliAppConf.CHANNEL,
    var platform: String = BiliAppConf.PLATFORM,
    var mobiApp: String = BiliAppConf.MOBI_APP,
    var model: String = BiliAppConf.model,
    var osVersion: String = BiliAppConf.osVersion,
    var network: Int = BiliAppConf.currentNetwork,
    var webViewVersion: Int = BiliWebConf.webViewVersion
) {
    var appUserAgent = ""
        private set
    var appUserAgentShort = ""
        private set
    var webUserAgent = ""
        private set

    fun buildUserAgents() {
        appUserAgent = buildAppUserAgent(withHttpAgent = true)
        // 文档 §4.2：非 GET 请求使用 a()，即“ os/android ...”
        appUserAgentShort = buildAppUserAgent(withHttpAgent = false)
        webUserAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$webViewVersion.0.0.0 Safari/537.36"
    }

    private fun buildAppUserAgent(withHttpAgent: Boolean): String {
        val httpAgent = System.getProperty("http.agent")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitize)
        val prefix = if (withHttpAgent) {
            if (httpAgent != null) "$httpAgent $version" else version
        } else {
            ""
        }
        val suffix = " os/$platform model/$model mobi_app/$mobiApp build/$buildCode" +
            " channel/$channel innerVer/$buildCode osVer/$osVersion network/$network"
        return if (withHttpAgent) "$prefix$suffix" else suffix
    }

    /** 非 ASCII / 控制字符替换成 ?，与官方 `kq2.i.e()` 一致。 */
    private fun sanitize(value: String): String =
        value.map { if (it.code in 0x20..0x7e) it else '?' }.joinToString("")
}

val BiliUserAgent: ClientPlugin<BiliUserAgentConfig> =
    createClientPlugin("BiliUserAgent", ::BiliUserAgentConfig) {
        pluginConfig.buildUserAgents()
        val appUserAgent = pluginConfig.appUserAgent
        val appUserAgentShort = pluginConfig.appUserAgentShort
        val webUserAgent = pluginConfig.webUserAgent
        onRequest { request, _ ->
            val isAppRequest = request.host == "app.bilibili.com" ||
                request.host == "passport.bilibili.com" ||
                request.url.parameters.contains("access_key") ||
                (request.body as? FormDataContent)?.formData?.contains("access_key") == true ||
                request.isMarkedAppRequest()
            val userAgent = when {
                !isAppRequest -> webUserAgent
                request.method == HttpMethod.Get -> appUserAgent
                else -> appUserAgentShort
            }
            LOGGER.trace("Adding User-Agent header: agent \"$userAgent\" for ${request.url}")
            request.header(HttpHeaders.UserAgent, userAgent)
        }
    }

@Suppress("FunctionName")
fun HttpClientConfig<*>.BiliUserAgent() {
    install(BiliUserAgent) {

    }
}
