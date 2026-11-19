package dev.aaa1115910.biliapi.repositories

import bilibili.app.playerunite.v1.PlayerGrpcKt
import bilibili.app.playerunite.v1.playViewUniteReq
import bilibili.community.service.dm.v1.DMGrpcKt
import bilibili.community.service.dm.v1.dmSegMobileReq
import bilibili.community.service.dm.v1.dmViewReq
import bilibili.pgc.gateway.player.v2.playViewReq
import bilibili.playershared.videoVod
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.CodeType
import dev.aaa1115910.biliapi.entity.PlayData
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMask
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskSegment
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskType
import dev.aaa1115910.biliapi.entity.video.HeartbeatVideoType
import dev.aaa1115910.biliapi.entity.video.Subtitle
import dev.aaa1115910.biliapi.entity.video.VideoShot
import dev.aaa1115910.biliapi.grpc.utils.handleGrpcException
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliHttpProxyApi
import dev.aaa1115910.biliapi.http.entity.danmaku.DanmakuData
import dev.aaa1115910.biliapi.http.util.AppRequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import bilibili.pgc.gateway.player.v2.PlayURLGrpcKt as PgcPlayURLGrpcKt

@Single
class VideoPlayRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository
) {
    private val playerStub
        get() = runCatching {
            PlayerGrpcKt.PlayerCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()
    private val pgcPlayUrlStub
        get() = runCatching {
            PgcPlayURLGrpcKt.PlayURLCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()
    private val danmakuStub
        get() = runCatching {
            DMGrpcKt.DMCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()

    private val proxyPgcPlayUrlStub
        get() = runCatching {
            PgcPlayURLGrpcKt.PlayURLCoroutineStub(channelRepository.proxyChannel!!)
        }.getOrNull()


    suspend fun getPlayData(
        aid: Long,
        cid: Long,
        preferApiType: ApiType = ApiType.Web
    ): PlayData {
        return when (preferApiType) {
            ApiType.Web -> {
                val playUrlData = BiliHttpApi.getVideoPlayUrl(
                    av = aid,
                    cid = cid,
                    fnval = HTTP_FNVAL,
                    qn = REQUEST_TOP_QN,
                    fnver = 0,
                    fourk = 1,
                    sessData = authRepository.sessionData,
                    dedeUserID = authRepository.mid,
                    gaiaVtoken = authRepository.gaiaVtoken,
                    // 未登录时网页接口默认只下发 480P，try_look 可拿到 1080P
                    tryLook = authRepository.sessionData.isNullOrBlank()
                ).getResponseData()
                PlayData.fromPlayUrlData(playUrlData)
            }

            ApiType.App -> {
                if (authRepository.accessToken.isNullOrBlank()) {
                    // 未登录时 gRPC 同样只下发 480P，改用 App 规则的 HTTP 接口并带 try_look
                    val playUrlData = BiliHttpApi.getAppVideoPlayUrl(
                        av = aid,
                        cid = cid,
                        fnval = HTTP_FNVAL,
                        qn = REQUEST_TOP_QN,
                        fnver = 0,
                        fourk = 1,
                        sessData = authRepository.sessionData,
                        dedeUserID = authRepository.mid,
                        gaiaVtoken = authRepository.gaiaVtoken,
                        tryLook = true
                    ).getResponseData()
                    PlayData.fromPlayUrlData(playUrlData)
                } else {
                    withContext(Dispatchers.IO) {
                        val codecTypes = listOf(
                            CodeType.Code264,
                            CodeType.Code265,
                            CodeType.CodeAv1
                        )
                        val replies = codecTypes.map { codecType ->
                            async {
                                val playUniteReply = runCatching {
                                    playerStub?.playViewUnite(playViewUniteReq {
                                        vod = videoVod {
                                            this.aid = aid
                                            this.cid = cid
                                            fnval = GRPC_FNVAL
                                            // proto 的 qn 是 uint64
                                            qn = REQUEST_TOP_QN.toLong()
                                            fnver = 0
                                            fourk = true
                                            // 2 = 强制返回 https 地址，部分设备/网络下 http 直连会失败
                                            forceHost = 2
                                            preferCodecType = codecType.toPlayerSharedCodeType()
                                        }
                                    }) ?: throw IllegalStateException("Player stub is not initialized")
                                }.onFailure {
                                    // dont throw
                                    runCatching { handleGrpcException(it) }
                                        .onFailure {
                                            println("get play data failed: [aid=$aid, cid=$cid, preferCodec=$codecType, preferApiType=$preferApiType]")
                                            it.printStackTrace()
                                        }
                                }.getOrNull()
                                playUniteReply
                            }
                        }.awaitAll()
                        val result = replies.map {
                            it?.let { PlayData.fromPlayViewUniteReply(it) }
                        }.reduce { acc, playData ->
                            acc?.let { playData?.let { acc + playData } ?: acc } ?: playData
                        } ?: throw IllegalStateException("All codec types are failed to get play data")
                        result
                    }
                }
            }
        }
    }

    suspend fun getPgcPlayData(
        aid: Long?,
        cid: Long?,
        epid: Int,
        preferCodec: CodeType = CodeType.NoCode,
        preferApiType: ApiType = ApiType.Web,
        enableProxy: Boolean = false,
        proxyArea: String = ""
    ): PlayData {
        println("get pgc play data: [aid=$aid, cid=$cid, epid=$epid, preferCodec=$preferCodec, preferApiType=$preferApiType, enableProxy=$enableProxy, proxyArea=$proxyArea]")
        return when (preferApiType) {
            ApiType.Web -> {
                val playUrlData = if (enableProxy) {
                    BiliHttpProxyApi.getPgcVideoPlayUrl(
                        av = aid,
                        cid = cid,
                        epid = epid,
                        fnval = HTTP_FNVAL,
                        qn = REQUEST_TOP_QN,
                        fnver = 0,
                        fourk = 1,
                        sessData = authRepository.sessionData,
                        dedeUserID = authRepository.mid,
                        gaiaVtoken = authRepository.gaiaVtoken
                    )
                } else {
                    BiliHttpApi.getPgcVideoPlayUrl(
                        av = aid,
                        cid = cid,
                        fnval = HTTP_FNVAL,
                        qn = REQUEST_TOP_QN,
                        fnver = 0,
                        fourk = 1,
                        sessData = authRepository.sessionData,
                        dedeUserID = authRepository.mid,
                        gaiaVtoken = authRepository.gaiaVtoken
                    )
                }.getResponseData()

                PlayData.fromPlayUrlData(playUrlData)
            }

            ApiType.App -> {
                withContext(Dispatchers.IO) {
                    val codecTypes = listOf(
                        CodeType.Code264,
                        CodeType.Code265,
                        CodeType.CodeAv1
                    )
                    val replies = codecTypes.map { codecType ->
                        val req = playViewReq {
                            this.epid = epid.toLong()
                            cid?.let { this.cid = it }
                            // proto 的 qn 是 uint64
                            qn = REQUEST_TOP_QN.toLong()
                            fnver = 0
                            fnval = GRPC_FNVAL
                            fourk = true
                            forceHost = 0
                            download = 0
                            preferCodecType = codecType.toPgcPlayUrlCodeType()
                        }
                        async {
                            val playReply = runCatching {
                                if (enableProxy) {
                                    proxyPgcPlayUrlStub?.playView(req)
                                        ?: throw IllegalStateException("Proxy pgc play url stub is not initialized")
                                } else {
                                    pgcPlayUrlStub?.playView(req)
                                        ?: throw IllegalStateException("Pgc play url stub is not initialized")
                                }
                            }.onFailure {
                                // dont throw
                                runCatching { handleGrpcException(it) }
                                    .onFailure {
                                        println("get pgc play data failed: [aid=$aid, cid=$cid, epid=$epid, preferCodec=$codecType, preferApiType=$preferApiType]")
                                        it.printStackTrace()
                                    }
                            }.getOrNull()
                            playReply
                        }
                    }.awaitAll()
                    val result = replies.map {
                        it?.let { PlayData.fromPgcPlayViewReply(it) }
                    }.reduce { acc, playData ->
                        acc?.let { playData?.let { acc + playData } ?: acc } ?: playData
                    } ?: throw IllegalStateException("All codec types are failed to get play data")
                    result
                }
            }
        }
    }

    suspend fun getSubtitle(
        aid: Long,
        cid: Long,
        preferApiType: ApiType = ApiType.Web
    ): List<Subtitle> {
        return when (preferApiType) {
            ApiType.Web -> {
                val response = BiliHttpApi.getVideoMoreInfo(
                    avid = aid,
                    cid = cid,
                    sessData = authRepository.sessionData ?: "",
                    buvid3 = authRepository.buvid3 ?: ""
                ).getResponseData()
                response.subtitle?.subtitles
                    ?.map { Subtitle.fromSubtitleItem(it) }
                    ?: emptyList()
            }

            ApiType.App -> {
                val dmViewReply = runCatching {
                    danmakuStub?.dmView(dmViewReq {
                        pid = aid.toLong()
                        oid = cid.toLong()
                        type = 1
                    })
                }.onFailure { handleGrpcException(it) }.getOrThrow()
                dmViewReply?.subtitle?.subtitlesList
                    ?.map { Subtitle.fromSubtitleItem(it) }
                    ?: emptyList()
            }
        }
    }

    suspend fun sendHeartbeat(
        aid: Long,
        cid: Long,
        time: Int,
        type: HeartbeatVideoType = HeartbeatVideoType.Video,
        subType: Int? = null,
        epid: Int? = null,
        seasonId: Int? = null,
        duration: Int? = null,
        scene: String? = null,
        preferApiType: ApiType = ApiType.Web
    ) {
        val result = when (preferApiType) {
            ApiType.Web -> BiliHttpApi.sendHeartbeat(
                avid = aid.toLong(),
                cid = cid,
                playedTime = time,
                type = type.value,
                subType = subType,
                epid = epid,
                sid = seasonId,
                csrf = authRepository.biliJct,
                sessData = authRepository.sessionData ?: ""
            )

            ApiType.App -> BiliHttpApi.sendHeartbeat(
                avid = aid.toLong(),
                cid = cid,
                playedTime = time,
                type = type.value,
                subType = subType,
                epid = epid,
                sid = seasonId,
                duration = duration,
                deviceTs = System.currentTimeMillis() / 1000,
                scene = scene,
                accessKey = authRepository.accessToken ?: ""
            )
        }
        println("send heartbeat result: $result")
    }

    /**
     * 文档 §11：App 模式优先用 gRPC `DM/DmSegMobile`（6 分钟一段），
     * 失败时回退到 XML 弹幕，保证老接口仍可用。
     */
    suspend fun getDanmakuData(
        aid: Long,
        cid: Long,
        preferApiType: ApiType = ApiType.Web
    ): List<DanmakuData> {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.getDanmakuXml(
                cid = cid,
                sessData = authRepository.sessionData ?: ""
            ).data

            ApiType.App -> runCatching {
                val result = mutableListOf<DanmakuData>()
                for (segment in 1..MAX_DANMAKU_SEGMENTS) {
                    val reply = danmakuStub?.dmSegMobile(dmSegMobileReq {
                        pid = aid
                        oid = cid
                        type = 1
                        segmentIndex = segment.toLong()
                        teenagersMode = if (AppRequestContext.teenagersMode) 1 else 0
                    }) ?: break
                    if (reply.elemsList.isEmpty()) break
                    result += reply.elemsList.map { elem ->
                        DanmakuData(
                            time = elem.progress / 1000f,
                            type = elem.mode,
                            size = elem.fontsize,
                            color = elem.color,
                            timestamp = elem.ctime.toInt(),
                            pool = elem.pool,
                            midHash = elem.midHash,
                            dmid = elem.id,
                            level = elem.weight,
                            text = elem.content
                        )
                    }
                }
                if (result.isEmpty()) throw IllegalStateException("empty danmaku")
                result
            }.getOrElse {
                BiliHttpApi.getDanmakuXml(
                    cid = cid,
                    sessData = authRepository.sessionData ?: ""
                ).data
            }
        }
    }

    companion object {
        /**
         * HTTP 播放接口（`/x/player/playurl` 及 pgc 变体）的 fnval：
         * 16 DASH | 64 HDR | 128 4K | 256 杜比音频 | 512 杜比视界 | 1024 AV1 | 2048。
         *
         * 实测（2026-11，BV1FykdYzELQ，web 签名 / 5 组 appkey 的 app 签名、
         * 带不带 access_key 结果一致）：`fnval >= 4096` 一律回 `-400 请求错误`，
         * 可用位只有 1/2/16/64/128/256/512/1024/2048。所以 **HTTP 通道拿不到 HDR Vivid**，
         * 与账号是不是大会员无关，换 appkey、加 access_key 都没用。
         */
        const val HTTP_FNVAL = 4048

        /**
         * gRPC（`PlayViewUnite` / pgc `playView`）的 fnval，在 [HTTP_FNVAL] 之上加 16384。
         *
         * 16384 就是 HDR Vivid（qn 129）的开关：只报 4048 时
         * `vod_info.stream_list` 最高只到 125（HDR 真彩）；加上 16384 才会出现
         * `quality=129 / format="hdflv2" / description="HDR Vivid"`。
         * 这个值拿到 HTTP 通道上会直接 -400，所以两条通道必须分开配。
         */
        const val GRPC_FNVAL = 20432

        /**
         * 请求的最高清晰度档位。取 129（HDR Vivid）而不是 127（8K）：
         * 清晰度码越大档位越高，129 > 127 > 126 > 125，只有把顶档报上去，
         * 账号/设备有权限时接口才会把 HDR Vivid 片源一起下发。
         * 请求一个片源不存在的档位不会报错，服务端按实际可给的档位返回。
         *
         * HTTP 的 qn 参数是 Int，gRPC 的 proto 字段是 uint64，用到的地方各自转换。
         */
        const val REQUEST_TOP_QN = 129

        /** 6 分钟一段，最多取 2 小时，避免异常视频无限翻页。 */
        private const val MAX_DANMAKU_SEGMENTS = 20
    }

    suspend fun getDanmakuMask(
        aid: Long,
        cid: Long,
        preferApiType: ApiType = ApiType.Web
    ): List<DanmakuMaskSegment> {
        val danmakuMaskUrl = when (preferApiType) {
            ApiType.Web -> {
                val response = BiliHttpApi.getVideoMoreInfo(
                    avid = aid,
                    cid = cid,
                    sessData = authRepository.sessionData ?: "",
                    buvid3 = authRepository.buvid3 ?: ""
                ).getResponseData()
                response.dmMask?.maskUrl
            }

            ApiType.App -> {
                val dmViewReply = runCatching {
                    danmakuStub?.dmView(dmViewReq {
                        pid = aid
                        oid = cid
                        type = 1
                    })
                }.onFailure { handleGrpcException(it) }.getOrThrow()
                dmViewReply?.mask?.maskUrl
            }
        } ?: return emptyList()

        val maskBinary = BiliHttpApi.download(danmakuMaskUrl.apply {
            when (preferApiType) {
                ApiType.Web -> replace("mobmask", "webmask")
                ApiType.App -> replace("webmask", "mobmask")
            }
        })
        val danmakuMaskType = when (preferApiType) {
            ApiType.Web -> DanmakuMaskType.WebMask
            ApiType.App -> DanmakuMaskType.MobMask
        }
        return DanmakuMask.fromBinary(maskBinary, danmakuMaskType).segments
    }

    suspend fun getVideoShot(
        aid: Long,
        cid: Long,
        preferApiType: ApiType = ApiType.Web
    ): VideoShot? {
        val videoShortResponse = when (preferApiType) {
            ApiType.Web -> BiliHttpApi.getWebVideoShot(aid = aid, cid = cid)
            ApiType.App -> BiliHttpApi.getAppVideoShot(aid = aid, cid = cid)
        }
        val videoShot = VideoShot.fromVideoShot(videoShortResponse.getResponseData())
        return videoShot
    }
}
