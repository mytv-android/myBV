package com.github.mytv.bv.network

import android.content.Context
import com.github.mytv.bv.BVApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 镜像的工作方式：[PREFIX] 把地址拼在原 URL 前面，[RAW_HOST] 只替换 raw 的主机名。 */
enum class MirrorKind { PREFIX, RAW_HOST }

/**
 * 一条可用的镜像线路；空串表示直连。
 *
 * 内置线路与 gitee 远程清单合并后都是这个形状，设置页、测速、检查更新与下载共用这一份。
 */
data class MirrorRoute(
    val id: String,
    val label: String,
    /** [MirrorKind.PREFIX] 是拼在原 URL 前的前缀；[MirrorKind.RAW_HOST] 只存主机名；直连为空串。 */
    val prefix: String,
    val kind: MirrorKind,
    /** 是否转发 `api.github.com`；不转发的线路只能下载资产与 raw 文件。 */
    val proxiesApi: Boolean,
    /** 排序档位，见 [TIER_FAST] 一族常量。 */
    val tier: Int,
    val enabled: Boolean = true,
) {
    /**
     * 为 [target] 拼出这条线路的 URL。线路不适用时返回 null——
     * 例如 raw_host 线路遇到 Release 资产地址，拼出来也是 404，不如直接不生成。
     */
    fun urlFor(target: String): String? = when (kind) {
        MirrorKind.PREFIX ->
            if (prefix.isEmpty()) target else prefix.trimEnd('/') + "/" + target
        MirrorKind.RAW_HOST ->
            if (target.startsWith(RAW_URL_PREFIX)) {
                target.replaceFirst(RAW_URL_PREFIX, "https://${prefix.trimEnd('/')}/")
            } else {
                null
            }
    }
}

/** 只服务 raw 的镜像就是替换这个主机。 */
private const val RAW_URL_PREFIX = "https://raw.githubusercontent.com/"

/**
 * 线路排序档位。铁律：镜像(0) → 国内直连(1) → 慢镜像(2) → 只服务 raw 的镜像(3)。
 * 顶层常量而不是枚举伴生对象里的，避免枚举条目依赖伴生对象的初始化顺序。
 */
const val TIER_MODE = -1
const val TIER_FAST = 0
const val TIER_DIRECT = 1
const val TIER_SLOW = 2
const val TIER_RAW_ONLY = 3

/** gitee `my-mirror` 仓库的清单结构，字段与 [MirrorRoute] 一一对应。 */
@Serializable
data class RemoteMirrorList(
    val schema: String = "",
    val mirrors: List<RemoteMirror> = emptyList(),
)

@Serializable
data class RemoteMirror(
    val id: String = "",
    val label: String = "",
    val base: String = "",
    val kind: String = "prefix",
    val api: Boolean = false,
    val tier: Int = 0,
    val enabled: Boolean = true,
)

/**
 * GitHub 加速镜像；空串表示直连。
 *
 * 2026-11-23 实测（Release 资产 / `api.github.com` / raw 三类端点，成都教育网出口）：
 * - gh-proxy.com / gh-proxy.org：三类全通，且代理 `api.github.com`。
 * - ghfile.geekertao.top / ghfast.top / gh.3w.pm / cors.isteed.cc：只转发资产与 raw，**不代理 API**。
 * - ghproxy.net：可用但 2~4.5s，仅作兜底。
 * - raw.githack.com / rawcdn.githack.com / githack.com：只服务 raw/blob，最后兜底。
 *
 * 已剔除（NXDOMAIN / TLS 不握手 / 403 只许 git / 变成落地页）：ghp.ci、hub.gitmirror.com、
 * github.moeyy.xyz、gh.cc、ghproxy.cn、gh-proxy.net、mirror.ghproxy.com、gh-proxy.ygxz.in、
 * gh.llkk.cc、gh.api.99988866.xyz、ghproxy.com、ghproxy.link、ghps.cc、gh.ddlc.top、
 * kkgithub.com、bgithub.xyz、githubfast.com、gitclone.com，以及 100 余个社区流传的 `gh.*`
 * 前缀代理。判定「活」的唯一标准是能前缀式抓回 raw 正文（HTTP 200 且不是 HTML 反爬页），
 * 网页能否打开不作数；实测脚本与完整记录见 gitee `mytv-android/my-mirror`。
 *
 * 运行时还会从 gitee `my-mirror` 拉最新清单与这里的内置值合并，见 [GithubRoutes]。
 * 排序铁律：**镜像 / 国内直连优先，raw 最后兜底**——按 [tier] 升序取，同档位保持声明顺序。
 */
enum class GithubMirror(
    val id: String,
    val label: String,
    /** 拼接在原始 URL 前面的前缀；空串表示直连。[MirrorKind.RAW_HOST] 时只存主机名。 */
    val prefix: String,
    val kind: MirrorKind,
    /** 是否代理 `api.github.com`（不代理的线路只能用于下载资产） */
    val proxiesApi: Boolean,
    val tier: Int,
) {
    /** 并发测速所有线路，按实测耗时排序（原有行为） */
    Auto("auto", "自动测速", "", MirrorKind.PREFIX, true, TIER_MODE),
    Direct("direct", "GitHub 直连", "", MirrorKind.PREFIX, true, TIER_DIRECT),
    ProxyOrg("gh-proxy.org", "gh-proxy.org", "https://gh-proxy.org/", MirrorKind.PREFIX, true, TIER_FAST),
    ProxyCom("gh-proxy.com", "gh-proxy.com", "https://gh-proxy.com/", MirrorKind.PREFIX, true, TIER_FAST),
    Geekertao("ghfile.geekertao.top", "ghfile.geekertao.top", "https://ghfile.geekertao.top/", MirrorKind.PREFIX, false, TIER_FAST),
    Ghfast("ghfast.top", "ghfast.top", "https://ghfast.top/", MirrorKind.PREFIX, false, TIER_FAST),
    ThreeW("gh.3w.pm", "gh.3w.pm", "https://gh.3w.pm/", MirrorKind.PREFIX, false, TIER_FAST),
    CorsIsteed("cors.isteed.cc", "cors.isteed.cc", "https://cors.isteed.cc/", MirrorKind.PREFIX, false, TIER_FAST),
    ProxyNet("ghproxy.net", "ghproxy.net", "https://ghproxy.net/", MirrorKind.PREFIX, false, TIER_SLOW),
    RawGithack("raw.githack.com", "raw.githack.com", "raw.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    RawcdnGithack("rawcdn.githack.com", "rawcdn.githack.com", "rawcdn.githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    Githack("githack.com", "githack.com", "githack.com", MirrorKind.RAW_HOST, false, TIER_RAW_ONLY),
    ;

    /** 是否是一条具体代理线路（自动测速与直连另行展开）。 */
    val isProxy: Boolean get() = this != Auto && this != Direct

    fun toRoute(): MirrorRoute =
        MirrorRoute(id, label, prefix, kind, proxiesApi, tier, enabled = true)

    companion object {
        val proxies: List<GithubMirror> = entries.filter { it.isProxy }

        fun fromName(name: String?): GithubMirror = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/**
 * GitHub 直连在国内不稳定，沿用 myDV 的做法：内置一份加速镜像清单，
 * 检查更新与下载前并发对所有线路做 HEAD 测速，按实测耗时决定顺序，失败后逐镜像回退。
 * 镜像以 `镜像前缀 + 原始 URL` 的形式转发，空串表示直连。
 *
 * 除内置清单外，还会从 gitee `mytv-android/my-mirror` 拉最新列表与内置值合并：
 * 加镜像、调顺序、下线失效镜像都改那个仓库，不用发新版本。
 */
internal object GithubRoutes {
    /** 单条线路的测速上限，超时按不可达处理。 */
    private const val PROBE_TIMEOUT_MS = 3_000L

    /**
     * 最新镜像清单。gitee 国内直连，本身就是给国内用户准备的。
     */
    private const val REMOTE_LIST_URL = "https://gitee.com/mytv-android/my-mirror/raw/master/mirrors.json"
    private const val PREFS_NAME = "mybv_mirrors"
    private const val SP_LIST = "mirror_list"
    private const val SP_LIST_AT = "mirror_list_at"
    private const val LIST_TTL_MS = 6 * 60 * 60 * 1000L
    private const val LIST_SCHEMA = "mytv.mirror/v1"
    /** 远程清单上限：挡住写飘了的列表把探测成本放大。 */
    private const val MAX_REMOTE_ENTRIES = 40
    private const val USER_AGENT = "myBV-Android"

    /** 内置线路顺序：tier 升序，同 tier 保持声明顺序。 */
    private val BUILT_IN_ROUTES: List<MirrorRoute> =
        GithubMirror.entries.filter { it != GithubMirror.Auto }.sortedBy { it.tier }.map { it.toRoute() }

    private val remoteJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val listClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Candidate(val label: String, val mirror: String, val url: String)

    /**
     * 一条线路的地址是否可接受。与 myHotpot 的 [com.github.mytv.myhotpot.data.remote.github.GitHubRoutes]
     * 用同一套标准：只接受 https 且 443、不带用户信息/查询/片段，主机不能是本机或内网。
     *
     * 这里**没有**「主机必须在白名单里」这道闸门——远程清单要能加新镜像，六个应用一视同仁。
     * 拦住危险主机的是下面这几条，与主机在不在表里无关。
     */
    private fun isSafeMirrorBase(base: String): Boolean {
        val url = base.trim().toHttpUrlOrNull() ?: return false
        if (url.scheme != "https" || url.port != 443) return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.query != null || url.fragment != null) return false
        return !isLocalMirrorHost(url.host)
    }

    /**
     * 主机是否只能指向本机或内网。按字面判断，不做 DNS 解析——
     * 解析会让结论依赖运行环境，而且这一层只是 URL 预检。
     */
    private fun isLocalMirrorHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") || normalized.endsWith(".lan") ||
            normalized.endsWith(".internal") || normalized.endsWith(".home.arpa")
        ) {
            return true
        }
        // 数字字面量（含 127.1、2130706433、0x7f000001 这类简写）一律按本地处理：
        // 公网镜像不会用 IP 字面量，用简写绕过的只有内网地址。
        return isNumericHost(normalized)
    }

    private fun isNumericHost(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare.isEmpty()) return false
        if (bare.all { it in '0'..'9' || it == '.' }) return true
        if (bare.contains(':')) return true
        if (bare.startsWith("0x", ignoreCase = true)) return true
        return bare.all { it in '0'..'9' } && bare.length in 8..10
    }

    private fun prefs(): android.content.SharedPreferences? =
        runCatching { BVApp.context }.getOrNull()
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 内置清单与远程清单合并后的线路顺序。远程拉不到就用内置——
     * 远程只是让新镜像不用发版本就能生效，不是必需项。
     */
    fun routes(): List<MirrorRoute> {
        val cached = runCatching { prefs()?.getString(SP_LIST, "").orEmpty() }.getOrNull().orEmpty()
        return mergeRoutes(BUILT_IN_ROUTES, parseRemote(cached).orEmpty())
    }

    /** 用户在设置里选了具体线路时，它排最前，其余线路与直连仍在后面兜底。 */
    fun routeOrder(selected: GithubMirror, target: String): List<MirrorRoute> {
        val applicable = routes().filter { it.urlFor(target) != null }
        return when (selected) {
            GithubMirror.Auto -> applicable
            GithubMirror.Direct -> applicable.filter { it.tier == TIER_DIRECT } +
                applicable.filterNot { it.tier == TIER_DIRECT }
            else -> {
                val pinned = applicable.firstOrNull { it.id == selected.id }
                if (pinned == null) applicable else listOf(pinned) + applicable.filterNot { it == pinned }
            }
        }
    }

    /** 选中线路打头覆盖测速结果：用户点名要走的线路不因一次测速慢就被跳过。 */
    fun applyPinned(order: List<MirrorRoute>, selected: GithubMirror): List<MirrorRoute> =
        if (selected.isProxy) {
            val pinned = order.firstOrNull { it.id == selected.id }
            if (pinned == null) order else listOf(pinned) + order.filterNot { it == pinned }
        } else {
            order
        }

    /**
     * 并发探测所有线路，返回按响应耗时升序排列的线路（含直连）。
     * 成功端点按耗时排前，失败/超时的保留在末尾继续参与回退。
     */
    suspend fun probeOrder(
        target: String,
        probe: suspend (String) -> Boolean,
    ): List<MirrorRoute> = coroutineScope {
        routes().filter { it.urlFor(target) != null }.map { route ->
            async {
                val startedAt = System.nanoTime()
                val reachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    try {
                        probe(route.urlFor(target) ?: return@withTimeoutOrNull false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        false
                    }
                } == true
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                route to if (reachable) elapsedMs else Long.MAX_VALUE
            }
        }.awaitAll()
            .sortedWith(compareBy({ it.second }, { it.first.label }))
            .map { it.first }
    }

    /** 按 [order] 生成候选线路；不在 order 里的线路补在末尾，避免过期缓存漏掉线路。 */
    fun candidates(url: String, order: List<MirrorRoute>): List<Candidate> {
        val known = routes().filter { it.urlFor(url) != null }
        val ranked = order + known.filterNot { it in order }
        return ranked.mapNotNull { route ->
            Candidate(
                label = if (route.prefix.isEmpty()) "直连" else route.label,
                mirror = route.id,
                url = route.urlFor(url) ?: return@mapNotNull null,
            )
        }.distinctBy { it.url }
    }

    /** 拉取 gitee 上的最新清单并写进缓存；成功返回 true，任何失败都只当没这回事。 */
    suspend fun refreshList(): Boolean = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext false
        try {
            val body = listClient.newCall(
                Request.Builder().url(REMOTE_LIST_URL).header("User-Agent", USER_AGENT).build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                response.body?.string().orEmpty()
            }
            if (parseRemote(body) == null) return@withContext false
            prefs.edit()
                .putString(SP_LIST, body)
                .putLong(SP_LIST_AT, System.currentTimeMillis())
                .apply()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** 缓存没过期就不拉；关键路径上可以无条件调它。 */
    suspend fun refreshListIfStale() {
        val cachedAt = runCatching { prefs()?.getLong(SP_LIST_AT, 0L) ?: 0L }.getOrDefault(0L)
        if (System.currentTimeMillis() - cachedAt < LIST_TTL_MS) return
        refreshList()
    }

    internal fun parseRemote(body: String): List<MirrorRoute>? {
        if (body.isBlank()) return null
        val parsed = runCatching { remoteJson.decodeFromString<RemoteMirrorList>(body) }
            .getOrNull() ?: return null
        if (parsed.schema != LIST_SCHEMA) return null
        return parsed.mirrors.take(MAX_REMOTE_ENTRIES).mapNotNull { entry ->
            if (entry.id.isBlank() || entry.base.isBlank()) return@mapNotNull null
            val kind = when (entry.kind.lowercase()) {
                "raw_host" -> MirrorKind.RAW_HOST
                else -> MirrorKind.PREFIX
            }
            val prefix = when (kind) {
                MirrorKind.PREFIX -> entry.base.trim().trimEnd('/') + "/"
                // raw_host 只存主机名
                MirrorKind.RAW_HOST -> entry.base.trim().trimEnd('/')
            }
            // raw_host 只存主机名，补成完整 URL 才能过校验
            val checkUrl = if (kind == MirrorKind.RAW_HOST) "https://$prefix/" else prefix
            // 过不了安全校验的就地丢掉：远程清单写飘了不该把请求指向本机或内网。
            if (!isSafeMirrorBase(checkUrl)) return@mapNotNull null
            MirrorRoute(
                id = entry.id,
                label = entry.label.ifBlank { entry.id },
                prefix = prefix,
                kind = kind,
                proxiesApi = entry.api,
                tier = entry.tier.coerceIn(TIER_FAST, TIER_RAW_ONLY),
                enabled = entry.enabled,
            )
        }
    }

    /**
     * 远程覆盖内置：同 id 整体替换（改地址、改档位、下线都行），新 id 追加。
     * 档位夹在 0..3，直连永远保留——远程清单再坏也换不掉「国内直连」这条兜底。
     */
    private fun mergeRoutes(
        builtIn: List<MirrorRoute>,
        remote: List<MirrorRoute>,
    ): List<MirrorRoute> {
        if (remote.isEmpty()) return builtIn
        val merged = LinkedHashMap<String, MirrorRoute>()
        builtIn.forEach { merged[it.id] = it }
        remote.forEach { entry ->
            val clamped = entry.copy(
                tier = entry.tier.coerceIn(TIER_FAST, TIER_RAW_ONLY),
                label = entry.label.ifBlank { entry.id },
            )
            merged[entry.id] = if (entry.id == GithubMirror.Direct.id) {
                clamped.copy(tier = TIER_DIRECT, enabled = true)
            } else {
                clamped
            }
        }
        return merged.values.filter { it.enabled }.sortedBy { it.tier }
    }
}
