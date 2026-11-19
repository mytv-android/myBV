package com.github.mytv.bv.repository

import com.github.mytv.bv.player.entity.Resolution
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.biliapi.repositories.ChannelRepository
import dev.aaa1115910.biliapi.repositories.VideoPlayRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HDR Vivid 契约（B 站清晰度码 129）：
 * 接口把 129 和 125（HDR 真彩）分开下发，客户端必须把它当成独立档位，
 * 否则 HDR Vivid 片源会被识别成普通 HDR，用户在清晰度里选不到它。
 *
 * 档位从高到低：129 HDR Vivid > 127 8K > 126 杜比视界 > 125 HDR 真彩 > 120 4K > …
 *
 * 光「认识 129」+「把 qn 报成 129」还不够：fnval 里必须带 16384 这一位，
 * 且必须走 gRPC（HTTP 播放接口对 fnval >= 4096 一律 -400）。
 * 详细实测见 docs/hdr-vivid-quality.md。
 */
class PlayUrlResolverQualityTest {

    private val resolver = PlayUrlResolver(
        VideoPlayRepository(AuthRepository(), ChannelRepository())
    )

    @Test
    fun `quality code 129 maps to its own hdr vivid tier`() {
        assertEquals(Resolution.RHdrVivid, Resolution.fromCode(129))
    }

    @Test
    fun `every quality code keeps its own tier`() {
        val expected = mapOf(
            6 to Resolution.R240P,
            16 to Resolution.R360P,
            32 to Resolution.R480P,
            64 to Resolution.R720P,
            74 to Resolution.R720P60,
            80 to Resolution.R1080P,
            112 to Resolution.R1080PPlus,
            116 to Resolution.R1080P60,
            120 to Resolution.R4K,
            125 to Resolution.RHdr,
            126 to Resolution.RDolby,
            127 to Resolution.R8K,
            129 to Resolution.RHdrVivid,
        )
        expected.forEach { (code, resolution) ->
            assertEquals(resolution, Resolution.fromCode(code), "清晰度码 $code 映射错误")
        }
    }

    @Test
    fun `hdr vivid ranks above 8k so it is offered first`() {
        val available = listOf(Resolution.RHdrVivid, Resolution.R8K, Resolution.RDolby, Resolution.RHdr)
        assertEquals(
            Resolution.RHdrVivid,
            available.sortedByDescending { it.code }.first(),
            "selectQuality 靠 code 降序取档，HDR Vivid 必须排在 8K 之前"
        )
    }

    @Test
    fun `requested hdr vivid is kept when the source has it`() {
        val available = listOf(Resolution.RHdrVivid, Resolution.R8K, Resolution.R1080P)
        assertEquals(
            Resolution.RHdrVivid,
            resolver.selectQuality(
                available = available,
                preferred = Resolution.RHdrVivid,
                current = Resolution.RHdrVivid
            )
        )
    }

    @Test
    fun `missing hdr vivid falls back to the highest tier below it`() {
        val available = listOf(Resolution.R8K, Resolution.RDolby, Resolution.RHdr, Resolution.R1080P)
        assertEquals(
            Resolution.R8K,
            resolver.selectQuality(
                available = available,
                preferred = Resolution.RHdrVivid,
                current = Resolution.RHdrVivid
            ),
            "片源没有 HDR Vivid 时应落到 8K，而不是普通 HDR"
        )
    }

    @Test
    fun `mobile data cap still wins over hdr vivid`() {
        val available = listOf(Resolution.RHdrVivid, Resolution.RHdr, Resolution.R1080P, Resolution.R720P)
        assertEquals(
            Resolution.R720P,
            resolver.selectQualityCapped(
                available = available,
                preferred = Resolution.RHdrVivid,
                maxQuality = Resolution.R720P
            )
        )
    }

    // ---- fnval 契约：光把 qn 报成 129 不够，fnval 里必须有 HDR Vivid 那一位 ----

    @Test
    fun `grpc fnval carries the hdr vivid bit`() {
        assertTrue(
            VideoPlayRepository.GRPC_FNVAL and 16384 != 0,
            "gRPC 的 fnval 必须带 16384（HDR Vivid 位），否则 stream_list 最高只到 125（HDR 真彩）"
        )
    }

    @Test
    fun `http fnval stays under the hard 4096 ceiling`() {
        assertTrue(
            VideoPlayRepository.HTTP_FNVAL < 4096,
            "HTTP 播放接口对 fnval >= 4096 一律回 -400 请求错误，实测 web / app 签名、带不带 access_key 都一样"
        )
    }

    @Test
    fun `grpc fnval is a strict superset of the http one`() {
        assertEquals(
            VideoPlayRepository.HTTP_FNVAL,
            VideoPlayRepository.GRPC_FNVAL and VideoPlayRepository.HTTP_FNVAL,
            "gRPC 要在 HTTP 的全部能力之上只加 HDR Vivid 那一位，不能弄丢 DASH / HDR / 4K / 杜比 / AV1"
        )
        assertTrue(VideoPlayRepository.GRPC_FNVAL > VideoPlayRepository.HTTP_FNVAL)
    }

    @Test
    fun `requested top qn is hdr vivid`() {
        assertEquals(
            129,
            VideoPlayRepository.REQUEST_TOP_QN,
            "请求的顶档必须是 129；报 127（8K）等于声明「最高只要 8K」，服务端就不会把 HDR Vivid 一起下发"
        )
        assertTrue(VideoPlayRepository.REQUEST_TOP_QN > 127, "顶档必须高于 8K（127）")
    }
}
