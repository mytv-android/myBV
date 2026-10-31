package com.github.mytv.bv.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GitHub 直连在国内不稳定，沿用 myDV 的做法：内置一份加速镜像清单，
 * 检查更新与下载前并发对所有线路做 HEAD 测速，按实测耗时决定顺序，失败后逐镜像回退。
 * 镜像以 `镜像前缀 + 原始 URL` 的形式转发，空串表示直连。
 */
internal object GithubRoutes {
    // 清单取自 myDV，另保留 myBV 此前实测可用的 gh.llkk.cc / ghfast.top。
    // 顺序只影响测速打平时的先后，实际顺序由 [probeOrder] 决定。
    private val MIRRORS = listOf(
        "https://gh-proxy.org/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://gh.cc/",
        "https://mirror.ghproxy.com/",
        "https://github.moeyy.xyz/",
        "https://ghproxy.cn/",
        "https://gh.api.99988866.xyz/",
        "https://gh-proxy.ygxz.in/",
        "https://gh.llkk.cc/",
        "https://ghfast.top/",
        "",
    )

    /** 单条线路的测速上限，超时按不可达处理。 */
    private const val PROBE_TIMEOUT_MS = 3_000L

    data class Candidate(val label: String, val mirror: String, val url: String)

    /**
     * 并发探测所有线路，返回按响应耗时升序排列的镜像前缀（含直连）。
     * 成功端点按耗时排前，失败/超时的保留在末尾继续参与回退。
     */
    suspend fun probeOrder(
        target: String,
        probe: suspend (String) -> Boolean,
    ): List<String> = coroutineScope {
        MIRRORS.map { mirror ->
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
        val ranked = order + MIRRORS.filterNot { it in order }
        return ranked.map { mirror ->
            Candidate(
                label = if (mirror.isEmpty()) "直连" else mirror.substringAfter("//").trimEnd('/'),
                mirror = mirror,
                url = mirror + url,
            )
        }
    }
}
