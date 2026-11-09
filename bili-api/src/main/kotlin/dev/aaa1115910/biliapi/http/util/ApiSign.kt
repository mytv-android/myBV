package dev.aaa1115910.biliapi.http.util

import dev.aaa1115910.biliapi.http.BiliHttpApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.clone
import io.ktor.http.encodedPath
import io.ktor.util.AttributeKey
import java.net.URLEncoder
import java.security.MessageDigest

private val SkipAddBuvid3CookieKey = AttributeKey<Boolean>("SkipAddBuvid3Cookie")

/** 标记不需要自动附加 buvid3/Web 指纹 cookie 的请求。 */
fun HttpRequestBuilder.skipAddBuvid3Cookie() {
    attributes.put(SkipAddBuvid3CookieKey, true)
}

/** 标记必须按 App 规则签名的请求（即使 host 不是 app.bilibili.com、query 里也没有 access_key）。 */
val AppRequestAttributeKey = AttributeKey<Boolean>("AppRequest")

fun HttpRequestBuilder.markAppRequest() {
    attributes.put(AppRequestAttributeKey, true)
}

fun HttpRequestBuilder.isMarkedAppRequest(): Boolean =
    attributes.getOrNull(AppRequestAttributeKey) == true

/**
 * 客户端 appkey。保持 HD 渠道的 appkey/appsec 配对，同时兼容文档列出的其它 appkey。
 */
private const val APP_KEY = "dfca71928277209b"
private const val DEFAULT_APP_SECRET = "560c52ccd288fed045859ed18bffd973"
private val APP_SECRETS = mapOf(
    "dfca71928277209b" to "b5475a8825547a4fc26c7d518eaaa02e",
    "1d8b6e7d45233436" to "560c52ccd288fed045859ed18bffd973",
    "bb3101000e232e27" to "36efcfed79309338ced0380abd824ac1",
    "07da50c9a0bf829f" to "25bdede4e1581c836cab73a48790ca6e",
)

private val mixinKeyEncTab = listOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
    26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
    20, 34, 44, 52
)

private fun appSecret(appkey: String) = APP_SECRETS[appkey] ?: DEFAULT_APP_SECRET

/** 与文档一致的 URL 编码：空格 %20、`*` 编码、`~` 不编码。 */
private fun encode(value: String): String =
    URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

/** 文档 §3.1 的公共参数 + §3.1 自定义参数；调用方显式传入的值优先。 */
private fun commonParams(): Map<String, String> = buildMap {
    put("platform", BiliAppConf.PLATFORM)
    put("mobi_app", BiliAppConf.MOBI_APP)
    put("appkey", APP_KEY)
    put("build", BiliAppConf.APP_BUILD_CODE.toString())
    put("channel", BiliAppConf.CHANNEL)
    put("c_locale", BiliAppConf.currentLocale)
    put("s_locale", BiliAppConf.systemLocale)
    put("ts", (System.currentTimeMillis() / 1000).toString())
    // 文档 §3.1：登录后由默认拦截器自动写入 access_key
    AppRequestContext.accessKeyProvider()?.takeIf { it.isNotBlank() }?.let { put("access_key", it) }
    putAll(BiliCustomParams.defaults())
    putAll(AppRequestContext.customParams)
}

private fun addCommonParams(url: URLBuilder) {
    commonParams().forEach { (key, value) ->
        when {
            // access_key 允许覆盖调用方传进来的空串，避免重复参数
            key == "access_key" && url.parameters[key].isNullOrEmpty() -> url.parameters[key] = value
            // 其他公共参数只补缺失键
            key != "access_key" && !url.parameters.contains(key) -> url.parameters.append(key, value)
        }
    }
}

private fun addCommonParams(params: ParametersBuilder) {
    commonParams().forEach { (key, value) ->
        when {
            key == "access_key" && params[key].isNullOrEmpty() -> params[key] = value
            key != "access_key" && params[key] == null -> params.append(key, value)
        }
    }
}

private fun sign(params: Map<String, String>, appkey: String): String {
    val raw = params.entries
        .sortedBy { it.key }
        .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    return MessageDigest.getInstance("MD5")
        .digest((raw + appSecret(appkey)).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/**
 * 文档 §4.1 的 HTTP 通用请求头。`x-bili-*-bin` 属于 gRPC 元数据，
 * 这里不再注入，避免 HTTP 请求画像与官方不一致。
 */
private fun HttpRequestBuilder.addAppHeaders() {
    val buvid = AppRequestContext.buvidProvider().orEmpty()

    if (buvid.isNotBlank()) headers["Buvid"] = buvid
    AppRequestContext.displayId?.takeIf { it.isNotBlank() }?.let { headers["Display-ID"] = it }
    AppRequestContext.deviceId?.takeIf { it.isNotBlank() }?.let { headers["Device-ID"] = it }
    AppRequestContext.fpLocal?.takeIf { it.isNotBlank() }?.let { headers["fp_local"] = it }
    AppRequestContext.fpRemote?.takeIf { it.isNotBlank() }?.let { headers["fp_remote"] = it }
    AppRequestContext.guestId?.takeIf { it.isNotBlank() }?.let { headers["GuestId"] = it }
    headers["session_id"] = AppRequestContext.foundationSessionId
}

private fun HttpRequestBuilder.isWbiRequest(): Boolean =
    url.encodedPath.contains("wbi") ||
        url.encodedPath.contains("/xlive/web-room/v1/index/getDanmuInfo")

private fun HttpRequestBuilder.hasAccessKeyInQueryOrBody(): Boolean {
    if (url.parameters.contains("access_key")) return true
    val form = (body as? FormDataContent)?.formData ?: return false
    return form.contains("access_key")
}

/**
 * 需要按 App 规则签名的请求：
 * - 发往 app.bilibili.com 的请求；
 * - 显式带 access_key 的请求（query 或 form）；
 * - passport 的 App 登录 POST（Web 扫码 GET 不签）。
 */
private fun HttpRequestBuilder.shouldAppSign(): Boolean {
    if (isMarkedAppRequest()) return true
    if (url.host == "app.bilibili.com") return true
    if (hasAccessKeyInQueryOrBody()) return true
    return method == HttpMethod.Post && url.host == "passport.bilibili.com"
}

/**
 * 供 JSON body 等非 form POST 手动使用：补 App 头、公共参数并签 URL。
 * 默认拦截器只处理 GET 与 form POST，JSON POST 需要调用方显式签一次。
 */
fun HttpRequestBuilder.encAppUrl() {
    if (url.parameters["sign"] != null) return
    addAppHeaders()
    encAppGet()
}

private fun HttpRequestBuilder.encAppGet() {
    if (url.parameters["sign"] != null) return
    addCommonParams(url)
    val params = url.parameters.entries()
        .associate { it.key to it.value.first() }
    val sign = sign(params, params["appkey"] ?: APP_KEY)
    url.parameters.remove("sign")
    url.parameters.append("sign", sign)
}

private fun HttpRequestBuilder.encAppPost() {
    val formData = (body as? FormDataContent)?.formData ?: return
    if (formData.contains("sign")) return

    // 文档 §2.2：query 与 body 合并后重新签名，签名串作为新的 form body
    val merged = ParametersBuilder()
    formData.entries().forEach { entry ->
        entry.value.forEach { merged.append(entry.key, it) }
    }
    url.parameters.entries().forEach { entry ->
        entry.value.forEach { merged.append(entry.key, it) }
    }
    url.parameters.clear()
    addCommonParams(merged)

    val params = merged.build().entries()
        .associate { it.key to it.value.first() }
    val sign = sign(params, params["appkey"] ?: APP_KEY)
    val signed = Parameters.build {
        params.filterKeys { it != "sign" }.forEach { (key, value) -> append(key, value) }
        append("sign", sign)
    }
    setBody(FormDataContent(signed))
}

suspend fun HttpRequestBuilder.encWbi() {
    val getMixinKey: (orig: String) -> String = { orig ->
        val mixinKey = mixinKeyEncTab.fold("") { s, i -> s + orig[i] }
        mixinKey.substring(0, 32)
    }

    if (BiliHttpApi.wbiImgKey == null || BiliHttpApi.wbiSubKey == null) BiliHttpApi.updateWbi()
    require(BiliHttpApi.wbiImgKey != null && BiliHttpApi.wbiSubKey != null) { "Wbi keys can't be null!" }
    val mixinKey = getMixinKey(BiliHttpApi.wbiImgKey + BiliHttpApi.wbiSubKey)

    val wts = (System.currentTimeMillis() / 1000).toInt()
    parameter("wts", wts)

    val sortedParams = url.encodedParameters.entries()
        .associate { it.key to it.value.first() }
        .toSortedMap()
        .map { (key, value) ->
            // 服务端计算 w_rid 前会剔除参数值里的 !'()* ，这里必须同步剔除，
            // 否则关键词里带这些字符（如 "NBA(2024)"）时签名对不上，返回 -400/被风控
            val filteredValue = value.filter { c -> c !in setOf('!', '\'', '(', ')', '*') }
            "$key=$filteredValue"
        }
        .joinToString("&")

    val wRid = MessageDigest.getInstance("MD5").digest((sortedParams + mixinKey).toByteArray())
        .joinToString("") { "%02x".format(it) }
    parameter("w_rid", wRid)
}

fun HttpClient.encApiSign() = plugin(HttpSend)
    .intercept { request ->
        // skip when using grpc proxy
        if (request.url.encodedPath.startsWith("bilibili.")) {
            return@intercept execute(request)
        }

        val getUrlWithoutAccessToken: (URLBuilder) -> String = { urlBuilder ->
            urlBuilder.clone().apply {
                if (parameters.contains("access_key") && !parameters["access_key"].isNullOrBlank()) {
                    parameters["access_key"] = "HIDDEN_ACCESS_TOKEN"
                }
            }.toString()
        }

        // access_key 可能出现在 query 或 form body 里，两种都视为 App 请求
        val isAppRequest = request.isMarkedAppRequest() ||
            request.url.parameters["access_key"] != null ||
            request.url.host == "app.bilibili.com" ||
            request.hasAccessKeyInQueryOrBody()
        val skipBuvid3Cookie = request.attributes.getOrNull(SkipAddBuvid3CookieKey) == true
        // Web 接口（含 WBI 签名接口）需要与 Web 端一致的 buvid 指纹，
        // 否则同一个账号在 App 与 Web 之间来回请求会触发风控
        if (!isAppRequest && !skipBuvid3Cookie) {
            val extraCookies = WebCookieManager.cookieHeader()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (extraCookies.isNotEmpty()) {
                val existingCookie = request.headers[HttpHeaders.Cookie].orEmpty()
                val existingNames = existingCookie.split(";")
                    .map { it.trim().substringBefore("=") }
                    .filter { it.isNotBlank() }
                    .toSet()
                val missingCookies = extraCookies.filter { it.substringBefore("=") !in existingNames }
                if (missingCookies.isNotEmpty()) {
                    request.headers[HttpHeaders.Cookie] = if (existingCookie.isBlank()) {
                        missingCookies.joinToString("; ")
                    } else {
                        "${missingCookies.joinToString("; ")}; $existingCookie"
                    }
                }
            }
        }

        when (request.method) {
            // app 端如果既用到了 wbi get 接口，也用到了 token 去请求，那是先计算 wbi sign 还是 app sign？
            // 目前看来需要计算 wbi sign 的接口之前忘记计算 app sign 都通过校验了🤯
            HttpMethod.Get -> {
                if (request.isWbiRequest()) {
                    println("Enc wbi for get request: ${getUrlWithoutAccessToken(request.url)}")
                    request.encWbi()
                } else if (request.shouldAppSign()) {
                    println("Enc app sign for get request: ${getUrlWithoutAccessToken(request.url)}")
                    request.addAppHeaders()
                    request.encAppGet()
                }
            }

            HttpMethod.Post -> {
                if (request.body is EmptyContent) return@intercept execute(request)
                if (request.body !is FormDataContent) return@intercept execute(request)
                if (request.shouldAppSign()) {
                    println("Enc app sign for post request: ${getUrlWithoutAccessToken(request.url)}")
                    request.addAppHeaders()
                    request.encAppPost()
                }
            }
        }
        execute(request)
    }
