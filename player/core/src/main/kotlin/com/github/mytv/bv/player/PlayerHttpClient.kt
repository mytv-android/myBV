package com.github.mytv.bv.player

import android.content.Context
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 播放器媒体请求共用的进程级 OkHttpClient。
 *
 * 构建时需读取系统 CA 库并追加 assets 证书，成本较高；且每个 OkHttpClient 自带独立连接池，
 * 按播放器实例创建会让连接池与线程数随播放轮次累积。这里进程内只构建一次。
 * UA / Referer 由各 ExoMediaPlayer 实例设置在各自的 OkHttpDataSource.Factory 上，不经过本 client。
 */
object PlayerHttpClient {
    /** 仅使用 IPv4：由应用设置写入，DNS 解析时实时读取，切换后无需重建 client。 */
    @Volatile
    var ipv4Only: Boolean = false

    @Volatile
    private var client: OkHttpClient? = null

    /** 获取共享 client；首次调用时构建，不要在主线程序列化触发首次构建。 */
    fun get(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: OkHttpUtil.generateCustomSslOkHttpClient(context)
                .newBuilder()
                .dns(Ipv4OnlyDns)
                .build()
                .also { client = it }
        }

    /** 在 IO 线程调用以预热，避开首次进播放器时的主线程构建等待。 */
    fun preheat(context: Context) {
        get(context)
    }

    /** 开启 [ipv4Only] 时过滤掉 AAAA 记录；解析不出 IPv4 时回退全部结果。 */
    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (!ipv4Only) return all
            return all.filter { it is Inet4Address }.ifEmpty { all }
        }
    }
}
