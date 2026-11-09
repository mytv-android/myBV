package dev.aaa1115910.biliapi.repositories

import bilibili.app.dynamic.v2.DynamicGrpcKt
import bilibili.app.dynamic.v2.Refresh
import bilibili.app.dynamic.v2.dynAllReq
import bilibili.app.dynamic.v2.dynDetailReq
import bilibili.app.dynamic.v2.dynSpaceReq
import bilibili.app.dynamic.v2.dynVideoReq
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.user.DynamicData
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideoData
import dev.aaa1115910.biliapi.entity.user.FollowedUser
import dev.aaa1115910.biliapi.entity.user.SpaceVideoData
import dev.aaa1115910.biliapi.entity.user.SpaceVideoOrder
import dev.aaa1115910.biliapi.entity.user.SpaceVideoPage
import dev.aaa1115910.biliapi.grpc.utils.handleGrpcException
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.entity.user.FollowAction
import dev.aaa1115910.biliapi.http.entity.user.FollowActionSource
import dev.aaa1115910.biliapi.http.entity.user.RelationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.math.ceil

@Single
class UserRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository
) {
    private val dynamicStub
        get() = runCatching {
            DynamicGrpcKt.DynamicCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()

    private suspend fun modifyFollow(
        mid: Long,
        action: FollowAction,
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        val response = when (preferApiType) {
            ApiType.Web -> {
                BiliHttpApi.modifyFollow(
                    mid = mid,
                    action = action,
                    actionSource = FollowActionSource.Space,
                    csrf = authRepository.biliJct,
                    sessData = authRepository.sessionData
                )
            }

            ApiType.App -> {
                BiliHttpApi.modifyFollow(
                    mid = mid,
                    action = action,
                    actionSource = FollowActionSource.Space,
                    accessKey = authRepository.accessToken
                )
            }
        }
        return response.code == 0
    }

    suspend fun followUser(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean = modifyFollow(mid, FollowAction.AddFollow, preferApiType)

    suspend fun unfollowUser(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean = modifyFollow(mid, FollowAction.DelFollow, preferApiType)

    suspend fun checkIsFollowing(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean? {
        if (authRepository.sessionData == null && authRepository.accessToken == null) return null
        return runCatching {
            val response = when (preferApiType) {
                ApiType.Web -> {
                    BiliHttpApi.getRelations(
                        mid = mid,
                        sessData = authRepository.sessionData
                    )
                }

                ApiType.App -> {
                    BiliHttpApi.getRelations(
                        mid = mid,
                        //移动端貌似并没有使用这个接口，目前该接口返回-663鉴权失败，直接改用sessdata获取
                        sessData = authRepository.sessionData
                        //accessKey = authRepository.accessToken
                    )
                }
            }.getResponseData()
            listOf(
                RelationType.Followed,
                RelationType.FollowedQuietly,
                RelationType.BothFollowed
            ).contains(response.relation.attribute)
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    //TODO 改成返回 关注数，粉丝数，黑名单数
    suspend fun getFollowingUpCount(
        mid: Long,
        preferApiType: ApiType
    ): Int {
        if (authRepository.sessionData == null && authRepository.accessToken == null) return 0
        return runCatching {
            val response = when (preferApiType) {
                ApiType.Web -> {
                    BiliHttpApi.getRelationStat(
                        mid = mid,
                        sessData = authRepository.sessionData
                    )
                }

                ApiType.App -> {
                    BiliHttpApi.getRelationStat(
                        mid = mid,
                        accessKey = authRepository.accessToken
                    )
                }
            }.getResponseData()
            response.following
        }.onFailure {
            it.printStackTrace()
        }.getOrNull() ?: 0
    }

    suspend fun addSeasonFollow(
        seasonId: Int,
        preferApiType: ApiType = ApiType.Web
    ): String {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.addSeasonFollow(
                seasonId = seasonId,
                csrf = authRepository.biliJct!!,
                sessData = authRepository.sessionData!!
            )

            ApiType.App -> BiliHttpApi.addSeasonFollow(
                seasonId = seasonId,
                accessKey = authRepository.accessToken!!
            )
        }.getResponseData().toast
    }

    suspend fun delSeasonFollow(
        seasonId: Int,
        preferApiType: ApiType = ApiType.Web
    ): String {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.delSeasonFollow(
                seasonId = seasonId,
                csrf = authRepository.biliJct!!,
                sessData = authRepository.sessionData!!
            )

            ApiType.App -> BiliHttpApi.delSeasonFollow(
                seasonId = seasonId,
                accessKey = authRepository.accessToken!!
            )
        }.getResponseData().toast
    }

    suspend fun getSpaceVideos(
        mid: Long,
        order: SpaceVideoOrder = SpaceVideoOrder.PubDate,
        page: SpaceVideoPage = SpaceVideoPage(),
        preferApiType: ApiType = ApiType.Web
    ): SpaceVideoData {
        return when (preferApiType) {
            ApiType.Web -> {
                val webSpaceVideoData = BiliHttpApi.getWebUserSpaceVideos(
                    mid = mid,
                    order = order.value,
                    pageNumber = page.nextWebPageNumber,
                    pageSize = page.nextWebPageSize,
                    sessData = authRepository.sessionData ?: "",
                    dedeUserID = authRepository.mid
                ).getResponseData()
                SpaceVideoData.fromWebSpaceVideoData(webSpaceVideoData)
            }

            ApiType.App -> {
                val appSpaceVideoData = BiliHttpApi.getAppUserSpaceVideos(
                    mid = mid,
                    lastAvid = page.lastAvid,
                    order = order.value,
                    accessKey = authRepository.accessToken ?: ""
                ).getResponseData()
                SpaceVideoData.fromAppSpaceVideoData(appSpaceVideoData)
            }
        }
    }

    suspend fun getDynamicVideos(
        page: Int,
        offset: String,
        updateBaseline: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicVideoData {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicList(
                    type = "video",
                    page = page,
                    offset = offset,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicVideoData.fromDynamicData(responseData)
            }

            ApiType.App -> {
                var result: DynamicVideoData? = null
                runCatching {
                    val dynVideoReply = dynamicStub?.dynVideo(dynVideoReq {
                        this.page = page
                        this.offset = offset
                        this.updateBaseline = updateBaseline
                        localTime = 8
                        refreshType =
                            if (offset == "") Refresh.refresh_new else Refresh.refresh_history
                    })
                    result = DynamicVideoData.fromDynamicData(
                        dynVideoReply ?: error("动态视频 gRPC 接口无响应")
                    )
                }.onFailure {
                    handleGrpcException(it)
                }
                result ?: error("动态视频 gRPC 接口无响应")
            }
        }
    }

    suspend fun getDynamics(
        page: Int,
        offset: String,
        updateBaseline: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicData {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicList(
                    type = "all",
                    page = page,
                    offset = offset,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicData.fromDynamicData(responseData)
            }

            ApiType.App -> {
                var result: DynamicData? = null
                runCatching {
                    val dynAllReply = dynamicStub?.dynAll(dynAllReq {
                        this.page = page
                        this.offset = offset
                        this.updateBaseline = updateBaseline
                        localTime = 8
                        refreshType =
                            if (offset == "") Refresh.refresh_new else Refresh.refresh_history
                    })
                    result = DynamicData.fromDynamicData(
                        dynAllReply ?: error("动态 gRPC 接口无响应")
                    )
                }.onFailure {
                    handleGrpcException(it)
                }
                result ?: error("动态 gRPC 接口无响应")
            }
        }
    }

    /**
     * 获取用户[hostMid]的动态列表（UP 主空间动态）。
     *
     * Web 走 /x/polymer/web-dynamic/v1/feed/space，App 走 gRPC 的 DynSpace
     * （空间页动态：host_uid + history_offset 分页，from=space）。
     * 关注动态流是分页的，不活跃的 UP 动态可能远在几十页之后，只在前端已加载的
     * 页面里筛永远筛不出来；这个接口直接按 UP 取，返回结构与 [getDynamics] 一致。
     *
     * @param hostMid 用户 uid
     * @param offset 请求第2页及其之后时填写，填写上一次请求获得的offset
     */
    suspend fun getSpaceDynamics(
        hostMid: Long,
        page: Int,
        offset: String,
        preferApiType: ApiType = ApiType.Web,
        sessData: String? = null
    ): DynamicData {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicSpaceList(
                    hostMid = hostMid,
                    page = page,
                    offset = offset,
                    sessData = sessData ?: authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicData.fromDynamicData(responseData)
            }

            ApiType.App -> {
                var result: DynamicData? = null
                runCatching {
                    val dynSpaceReply = dynamicStub?.dynSpace(dynSpaceReq {
                        this.hostUid = hostMid
                        this.historyOffset = offset
                        this.page = page.toLong()
                        localTime = 8
                        from = "space"
                    })
                    result = DynamicData.fromDynamicData(
                        dynSpaceReply ?: error("空间动态 gRPC 接口无响应")
                    )
                }.onFailure {
                    handleGrpcException(it)
                }
                result ?: error("空间动态 gRPC 接口无响应")
            }
        }
    }

    suspend fun getDynamicDetail(        dynamicId: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicItem {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicDetail(
                    id = dynamicId,
                    features = "itemOpusStyle",
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicItem.fromDynamicItem(responseData.item)
            }

            ApiType.App -> {
                var result: DynamicItem? = null
                runCatching {
                    val dynDetailReply = dynamicStub?.dynDetail(dynDetailReq {
                        this.dynamicId = dynamicId
                        localTime = 8
                    })
                    result = DynamicItem.fromDynamicItem(
                        dynDetailReply?.item ?: error("动态详情 gRPC 接口无响应")
                    )
                }.onFailure {
                    handleGrpcException(it)
                }
                result ?: error("动态详情 gRPC 接口无响应")
            }
        }
    }

    suspend fun getFollowedUsers(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): List<FollowedUser> {
        return when (preferApiType) {
            ApiType.Web -> {
                val result = mutableListOf<FollowedUser>()
                val firstResponse = BiliHttpApi.getUserFollow(
                    mid = mid,
                    sessData = authRepository.sessionData!!
                ).getResponseData()
                val userCount = firstResponse.total
                val pageCount = ceil((userCount.toFloat() / 50)).toInt()
                result.addAll(firstResponse.list.map { FollowedUser.fromHttpFollowedUser(it) })
                withContext(Dispatchers.IO) {
                    (2..pageCount).map { pageNumber ->
                        async {
                            BiliHttpApi.getUserFollow(
                                mid = mid,
                                pageNumber = pageNumber,
                                sessData = authRepository.sessionData!!
                            ).getResponseData()
                        }
                    }.awaitAll().forEach { userFollowData ->
                        result.addAll(userFollowData.list.map { FollowedUser.fromHttpFollowedUser(it) })
                    }
                }
                result
            }

            ApiType.App -> {
                val result = mutableListOf<FollowedUser>()
                val firstResponse = BiliHttpApi.getUserFollow(
                    mid = mid,
                    accessKey = authRepository.accessToken!!
                ).getResponseData()
                val userCount = firstResponse.total
                val pageCount = ceil((userCount.toFloat() / 50)).toInt()
                result.addAll(firstResponse.list.map { FollowedUser.fromHttpFollowedUser(it) })
                withContext(Dispatchers.IO) {
                    (2..pageCount).map { pageNumber ->
                        async {
                            BiliHttpApi.getUserFollow(
                                mid = mid,
                                pageNumber = pageNumber,
                                accessKey = authRepository.accessToken!!
                            ).getResponseData()
                        }
                    }.awaitAll().forEach { userFollowData ->
                        result.addAll(userFollowData.list.map { FollowedUser.fromHttpFollowedUser(it) })
                    }
                }
                result
            }
        }
    }
}