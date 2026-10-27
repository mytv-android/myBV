package com.github.mytv.bv.network

import com.github.mytv.bv.player.entity.SponsorSegment
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 空降助手（BilibiliSponsorBlock）公开接口。
 *
 * 只拉取网友标注的 [SPONSOR_CATEGORIES] 两类片段；请求不携带任何 B 站 cookie，
 * 失败或 404 都返回空列表，不影响正常播放。
 */
object SponsorBlockApi {
    private val logger = KotlinLogging.logger("SponsorBlockApi")

    private val hosts = listOf("https://bsbsb.top", "https://bsbsb.cc")
    private val sponsorCategories = setOf("sponsor", "selfpromo")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        BrowserUserAgent()
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
    }

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    @Serializable
    private data class SegmentDto(
        val segment: List<Double> = emptyList(),
        val cid: String? = null,
        val category: String = "",
        val actionType: String = "",
        @SerialName("UUID") val uuid: String = "",
    )

    suspend fun getSegments(bvid: String, cid: Long): List<SponsorSegment> {
        if (bvid.isBlank() || cid <= 0L) return emptyList()
        val cacheKey = "$bvid:$cid"
        cache[cacheKey]?.let { return it }

        for (host in hosts) {
            try {
                val response = client.get("$host/api/skipSegments") {
                    parameter("videoID", bvid)
                    parameter("cid", cid)
                }
                if (response.status == HttpStatusCode.NotFound) {
                    cache[cacheKey] = emptyList()
                    return emptyList()
                }
                if (!response.status.isSuccess()) {
                    logger.warn { "Empty sponsor segments for $cacheKey: ${response.status}" }
                    continue
                }
                val segments = parseSegments(response.bodyAsText())
                if (cache.size > 128) cache.clear()
                cache[cacheKey] = segments
                return segments
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch sponsor segments from $host" }
            }
        }
        return emptyList()
    }

    private fun parseSegments(body: String): List<SponsorSegment> = runCatching {
        json.decodeFromString<List<SegmentDto>>(body)
    }.getOrDefault(emptyList())
        .asSequence()
        .filter { it.actionType == "skip" && it.category in sponsorCategories }
        .mapNotNull { dto ->
            val start = dto.segment.getOrNull(0) ?: return@mapNotNull null
            val end = dto.segment.getOrNull(1) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            SponsorSegment(
                start = start,
                end = end,
                category = dto.category,
            )
        }
        .sortedBy { it.start }
        .toList()
}
