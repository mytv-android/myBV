package com.github.mytv.bv.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GitHub 加速镜像；空串表示直连。
 *
 * 2026-11-17 实测（Release 资产 / `api.github.com` / raw 三类端点）：
 * - 直连：资产与 raw 可用；`api.github.com` 常因共享出口 IP 被限流 403。
 * - gh-proxy.org / gh-proxy.com：三类全通，且代理 `api.github.com`。
 * - ghfile.geekertao.top / ghfast.top / gh.3w.pm：只转发资产与 raw，**不代理 API**。
 * - ghproxy.net：资产可用但 6~22s，仅作兜底。
 *
 * 已剔除（NXDOMAIN / TLS 不握手 / 落地页）：ghp.ci、hub.gitmirror.com、github.moeyy.xyz、
 * gh.cc、ghproxy.cn、gh-proxy.net、mirror.ghproxy.com、gh-proxy.ygxz.in、gh.llkk.cc、
 * gh.api.99988866.xyz、ghproxy.com。
 */
enum class GithubMirror(val label: String, val prefix: String) {
    /** 并发测速所有线路，按实测耗时排序（原有行为） */
    Auto("自动测速", ""),
    Direct("GitHub 直连", ""),
    ProxyOrg("gh-proxy.org", "https://gh-proxy.org/"),
    ProxyCom("gh-proxy.com", "https://gh-proxy.com/"),
    Geekertao("ghfile.geekertao.top", "https://ghfile.geekertao.top/"),
    Ghfast("ghfast.top", "https://ghfast.top/"),
    ThreeW("gh.3w.pm", "https://gh.3w.pm/"),
    ProxyNet("ghproxy.net", "https://ghproxy.net/"),
    ;

    /** 是否是一条具体代理线路（自动测速与直连另行展开）。 */
    val isProxy: Boolean get() = this != Auto && this != Direct

    companion object {
        val proxies: List<GithubMirror> = entries.filter { it.isProxy }

        fun fromName(name: String?): GithubMirror = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/**
 * GitHub 直连在国内不稳定，沿用 myDV 的做法：内置一份加速镜像清单，
 * 检查更新与下载前并发对所有线路做 HEAD 测速，按实测耗时决定顺序，失败后逐镜像回退。
 * 镜像以 `镜像前缀 + 原始 URL` 的形式转发，空串表示直连。
 */
internal object GithubRoutes {
    /** 单条线路的测速上限，超时按不可达处理。 */
    private const val PROBE_TIMEOUT_MS = 3_000L

    data class Candidate(val label: String, val mirror: String, val url: String)

    /** 用户在设置里选了具体线路时，它排最前，其余线路与直连仍在后面兜底。 */
    fun routeOrder(selected: GithubMirror): List<String> {
        val proxies = GithubMirror.proxies.map { it.prefix }
        return when (selected) {
            GithubMirror.Auto -> proxies + ""
            GithubMirror.Direct -> listOf("") + proxies
            else -> listOf(selected.prefix) + proxies.filterNot { it == selected.prefix } + ""
        }
    }

    /** 选中线路打头覆盖测速结果：用户点名要走的线路不因一次测速慢就被跳过。 */
    fun applyPinned(order: List<String>, selected: GithubMirror): List<String> =
        if (selected.isProxy) listOf(selected.prefix) + order.filterNot { it == selected.prefix }
        else order

    /**
     * 并发探测所有线路，返回按响应耗时升序排列的镜像前缀（含直连）。
     * 成功端点按耗时排前，失败/超时的保留在末尾继续参与回退。
     */
    suspend fun probeOrder(
        target: String,
        probe: suspend (String) -> Boolean,
    ): List<String> = coroutineScope {
        GithubMirror.proxies.map { it.prefix }.plus("").map { mirror ->
            async {
                val startedAt = System.nanoTime()
                val reachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    try {
                        probe(mirror + target)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        false
                    }
                } == true
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                mirror to if (reachable) elapsedMs else Long.MAX_VALUE
            }
        }.awaitAll()
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
    }

    /** 按 [order] 生成候选线路；不在 order 里的镜像补在末尾，避免过期缓存漏掉线路。 */
    fun candidates(url: String, order: List<String>): List<Candidate> {
        val known = GithubMirror.proxies.map { it.prefix } + ""
        val ranked = order + known.filterNot { it in order }
        return ranked.map { mirror ->
            Candidate(
                label = if (mirror.isEmpty()) "直连" else mirror.substringAfter("//").trimEnd('/'),
                mirror = mirror,
                url = mirror + url,
            )
        }
    }
}
