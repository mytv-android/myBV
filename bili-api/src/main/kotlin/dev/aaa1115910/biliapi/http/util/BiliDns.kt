package dev.aaa1115910.biliapi.http.util

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 全局 DNS 过滤：[BiliAppConf.ipv4Only] 开启时只返回 IPv4 地址，
 * 避开部分电视盒子 / 路由器 IPv6 路由异常导致请求先在 IPv6 上超时。
 * 一个 A 记录都解析不出来时回退全部结果，不在纯 IPv6 网络下直接失败。
 */
object BiliDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (!BiliAppConf.ipv4Only) return all
        return all.filter { it is Inet4Address }.ifEmpty { all }
    }
}
