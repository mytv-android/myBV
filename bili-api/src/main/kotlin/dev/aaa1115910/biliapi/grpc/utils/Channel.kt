package dev.aaa1115910.biliapi.grpc.utils

import bilibili.metadata.device.device
import bilibili.metadata.fawkes.fawkesReq
import bilibili.metadata.locale.LocaleIds
import bilibili.metadata.locale.locale
import bilibili.metadata.locale.localeIds
import bilibili.metadata.metadata
import bilibili.metadata.network.NetworkType
import bilibili.metadata.network.network
import bilibili.metadata.parabox.exps
import bilibili.metadata.restriction.ModeType
import bilibili.metadata.restriction.restriction
import dev.aaa1115910.biliapi.http.util.AppRequestContext
import dev.aaa1115910.biliapi.http.util.BiliAppConf
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.grpc.Metadata as GrpcMetadata

private const val RPC_TIMEOUT_SECONDS = 10L

fun generateChannel(
    accessKey: String,
    buvid: String,
    mid: Long? = null,
    endPoint: String = BiliAppConf.GRPC_HOST,
    port: Int = BiliAppConf.GRPC_PORT,
    enableTransportSecurity: Boolean = true
): ManagedChannel {
    val builder = if (BiliAppConf.ipv4Only) {
        // grpc 自带 DNS 解析没有 IPv4 过滤入口：先解析出 A 记录 IP 直连，
        // overrideAuthority 保持原域名，TLS SNI / 证书校验不受影响
        val ipv4 = runCatching {
            InetAddress.getAllByName(endPoint).firstOrNull { it is Inet4Address }
        }.getOrNull()
        if (ipv4 != null) {
            ManagedChannelBuilder
                .forAddress(ipv4.hostAddress, port)
                .overrideAuthority("$endPoint:$port")
        } else {
            ManagedChannelBuilder.forAddress(endPoint, port)
        }
    } else {
        ManagedChannelBuilder.forAddress(endPoint, port)
    }
    return builder
        .apply { if (enableTransportSecurity) useTransportSecurity() else usePlaintext() }
        .executor(Dispatchers.IO.asExecutor())
        .intercept(MetadataInterceptor(accessKey, buvid, mid))
        .build()
}

private class MetadataInterceptor(
    private val accessKey: String,
    private val buvid: String,
    private val mid: Long?
) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions, next: Channel
    ): ClientCall<ReqT, RespT> {
        val options = callOptions.withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return object : SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, options)) {
            override fun start(responseListener: Listener<RespT>, headers: GrpcMetadata) {
                headers.apply {
                    if (accessKey.isNotBlank()) putAuthorization(accessKey)
                    putMetadataBin(accessKey, buvid)
                    putDeviceBin(buvid)
                    putLocaleBin()
                    putNetworkBin()
                    putRestrictionBin()
                    putExpsBin()
                    putFawkesBin()
                    putTraceId()
                    putBuvidHeader(buvid)
                    putMidHeader(mid)
                }
                super.start(responseListener, headers)
            }
        }
    }
}

fun GrpcMetadata.putAuthorization(accessKey: String) {
    put(
        GrpcMetadata.Key.of("authorization", GrpcMetadata.ASCII_STRING_MARSHALLER),
        "identify_v1 $accessKey"
    )
}

fun GrpcMetadata.putMetadataBin(accessKey: String, buvid: String) {
    put(
        GrpcMetadata.Key.of("x-bili-metadata-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        metadata {
            this.accessKey = accessKey
            mobiApp = BiliAppConf.MOBI_APP
            device = BiliAppConf.DEVICE
            build = BiliAppConf.APP_BUILD_CODE
            channel = BiliAppConf.CHANNEL
            this.buvid = buvid
            platform = BiliAppConf.PLATFORM
        }.toByteArray()
    )
}

fun GrpcMetadata.putDeviceBin(buvid: String) {
    val fpLocal = AppRequestContext.fpLocal.orEmpty()
    val fpRemote = AppRequestContext.fpRemote.orEmpty()
    put(
        io.grpc.Metadata.Key.of("x-bili-device-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        device {
            appId = BiliAppConf.APP_ID
            build = BiliAppConf.APP_BUILD_CODE
            this.buvid = buvid
            mobiApp = BiliAppConf.MOBI_APP
            platform = BiliAppConf.PLATFORM
            device = BiliAppConf.DEVICE
            channel = BiliAppConf.CHANNEL
            brand = BiliAppConf.brand
            model = BiliAppConf.model
            osver = BiliAppConf.osVersion
            versionName = BiliAppConf.APP_VERSION_NAME
            this.fpLocal = fpLocal
            this.fpRemote = fpRemote
            fp = fpLocal.ifBlank { fpRemote }
            fts = AppRequestContext.firstLaunchTime
        }.toByteArray()
    )
}

fun GrpcMetadata.putLocaleBin() {
    val cLocale = localeIdsOf(BiliAppConf.currentLocale)
    val sLocale = localeIdsOf(BiliAppConf.systemLocale)
    put(
        io.grpc.Metadata.Key.of("x-bili-locale-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        locale {
            this.cLocale = cLocale
            this.sLocale = sLocale
            timezone = BiliAppConf.TIMEZONE
        }.toByteArray()
    )
}

fun GrpcMetadata.putNetworkBin() {
    put(
        io.grpc.Metadata.Key.of("x-bili-network-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        network {
            type = when (BiliAppConf.currentNetwork) {
                2 -> NetworkType.WIFI
                1 -> NetworkType.CELLULAR
                else -> NetworkType.OTHERNET
            }
        }.toByteArray()
    )
}

fun GrpcMetadata.putRestrictionBin() {
    put(
        io.grpc.Metadata.Key.of("x-bili-restriction-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        restriction {
            teenagersMode = AppRequestContext.teenagersMode
            lessonsMode = false
            mode = if (AppRequestContext.teenagersMode) ModeType.TEENAGERS else ModeType.NORMAL
            review = false
            disableRcmd = AppRequestContext.disableRcmd
        }.toByteArray()
    )
}

fun GrpcMetadata.putExpsBin() {
    put(
        io.grpc.Metadata.Key.of("x-bili-exps-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        exps { }.toByteArray()
    )
}

fun GrpcMetadata.putFawkesBin() {
    put(
        GrpcMetadata.Key.of("x-bili-fawkes-req-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
        fawkesReq {
            appkey = "android64"
            env = "prod"
            sessionId = AppRequestContext.sessionId
        }.toByteArray()
    )
}

fun GrpcMetadata.putTraceId() {
    put(
        GrpcMetadata.Key.of("x-bili-trace-id", GrpcMetadata.ASCII_STRING_MARSHALLER),
        UUID.randomUUID().toString().replace("-", "")
    )
}

fun GrpcMetadata.putBuvidHeader(buvid: String) {
    if (buvid.isBlank()) return
    put(
        GrpcMetadata.Key.of("buvid", GrpcMetadata.ASCII_STRING_MARSHALLER),
        buvid
    )
}

fun GrpcMetadata.putMidHeader(mid: Long?) {
    put(
        GrpcMetadata.Key.of("x-bili-mid", GrpcMetadata.ASCII_STRING_MARSHALLER),
        (mid ?: 0L).toString()
    )
}

private fun localeIdsOf(tag: String): LocaleIds {
    val locale = runCatching { java.util.Locale.forLanguageTag(tag) }.getOrNull()
    val language = locale?.language?.takeIf { it.isNotBlank() } ?: "zh"
    val script = locale?.script?.takeIf { it.isNotBlank() }
        ?: if (language == "zh") "Hans" else ""
    val region = locale?.country?.takeIf { it.isNotBlank() }
        ?: if (language == "zh") "CN" else ""
    return localeIds {
        this.language = language
        this.script = script
        this.region = region
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun base64NoPadding(bytes: ByteArray): String = Base64.encode(bytes).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
fun HttpRequestBuilder.generateGrpcProxyHeaders(
    accessKey: String,
    buvid: String,
    mid: Long? = null
) {
    header("authorization", "identify_v1 $accessKey")
    header("buvid", buvid)
    header("x-bili-mid", (mid ?: 0L).toString())
    header("x-bili-trace-id", UUID.randomUUID().toString().replace("-", ""))
    header("x-bili-metadata-bin", base64NoPadding(metadata {
        this.accessKey = accessKey
        mobiApp = BiliAppConf.MOBI_APP
        device = BiliAppConf.DEVICE
        build = BiliAppConf.APP_BUILD_CODE
        channel = BiliAppConf.CHANNEL
        this.buvid = buvid
        platform = BiliAppConf.PLATFORM
    }.toByteArray()))
    val fpLocal = AppRequestContext.fpLocal.orEmpty()
    val fpRemote = AppRequestContext.fpRemote.orEmpty()
    header("x-bili-device-bin", base64NoPadding(device {
        appId = BiliAppConf.APP_ID
        build = BiliAppConf.APP_BUILD_CODE
        this.buvid = buvid
        mobiApp = BiliAppConf.MOBI_APP
        platform = BiliAppConf.PLATFORM
        device = BiliAppConf.DEVICE
        channel = BiliAppConf.CHANNEL
        brand = BiliAppConf.brand
        model = BiliAppConf.model
        osver = BiliAppConf.osVersion
        versionName = BiliAppConf.APP_VERSION_NAME
        this.fpLocal = fpLocal
        this.fpRemote = fpRemote
        fp = fpLocal.ifBlank { fpRemote }
        fts = AppRequestContext.firstLaunchTime
    }.toByteArray()))
    val cLocale = localeIdsOf(BiliAppConf.currentLocale)
    val sLocale = localeIdsOf(BiliAppConf.systemLocale)
    header("x-bili-locale-bin", base64NoPadding(locale {
        this.cLocale = cLocale
        this.sLocale = sLocale
        timezone = BiliAppConf.TIMEZONE
    }.toByteArray()))
    header("x-bili-network-bin", base64NoPadding(network {
        type = when (BiliAppConf.currentNetwork) {
            2 -> NetworkType.WIFI
            1 -> NetworkType.CELLULAR
            else -> NetworkType.OTHERNET
        }
    }.toByteArray()))
    header("x-bili-restriction-bin", base64NoPadding(restriction {
        teenagersMode = AppRequestContext.teenagersMode
        lessonsMode = false
        mode = if (AppRequestContext.teenagersMode) ModeType.TEENAGERS else ModeType.NORMAL
        review = false
        disableRcmd = AppRequestContext.disableRcmd
    }.toByteArray()))
    header("x-bili-exps-bin", base64NoPadding(exps { }.toByteArray()))
    header("x-bili-fawkes-req-bin", base64NoPadding(fawkesReq {
        appkey = "android64"
        env = "prod"
        sessionId = AppRequestContext.sessionId
    }.toByteArray()))
}
