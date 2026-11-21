package com.github.mytv.bv.player.entity

interface VideoListItem {
    /**
     * 选集面板（LazyColumn / LazyRow）的 item key。
     *
     * 接口并不保证剧集 / 分 P 不重复：同一个 cid 会被重复下发，「跳转到其它 PGC」的占位剧集
     * cid 还是 0。重复 key 会直接抛 IllegalArgumentException（「Key "0" was already used」）
     * 把界面带走（1.log：番剧详情 800+ 集，进页即崩）。选集列表的唯一出口
     * VideoInfoRepository.replaceVideoList 按它去重，保证唯一。
     */
    val lazyKey: String
}

open class VideoListItemData(
    open val aid: Long,
    open val cid: Long,
    open val epid: Int? = null,
    open val seasonId: Int? = null,
    open val title: String,
    open val index: Int,
) : VideoListItem {
    /** 互动视频分支用的分支名；普通选集没有，恒为空串 */
    open val partTitle: String = ""

    override val lazyKey: String get() = "video:$aid:$cid"
}

data class VideoListPart(
    override val aid: Long,
    override val cid: Long,
    override val epid: Int? = null,
    override val seasonId: Int? = null,
    override val title: String,
    override val index: Int,
) : VideoListItemData(aid, cid, epid, seasonId, title, index) {
    override val lazyKey: String get() = "part:$aid:$cid"
}

data class VideoListUgcEpisode(
    override val aid: Long,
    override val cid: Long,
    override val epid: Int? = null,
    override val seasonId: Int? = null,
    override val title: String,
    override val index: Int,
) : VideoListItemData(aid, cid, epid, seasonId, title, index) {
    override val lazyKey: String get() = "ugc:$aid:$cid"
}

data class VideoListUgcEpisodeTitle(
    val index: Int,
    val title: String
) : VideoListItem {
    override val lazyKey: String get() = "section:$index:$title"
}

data class VideoListPgcEpisode(
    override val aid: Long,
    override val cid: Long,
    override val epid: Int? = null,
    override val seasonId: Int? = null,
    override val title: String,
    override val index: Int,
) : VideoListItemData(aid, cid, epid, seasonId, title, index) {
    override val lazyKey: String get() = "episode:$seasonId:$epid:$cid"
}

/** 互动视频的分支（当前节点的待选项，或已走过的节点路径，用于回退） */
data class VideoListInteractiveNode(
    override val aid: Long,
    override val cid: Long,
    override val epid: Int? = null,
    override val seasonId: Int? = null,
    override val title: String,
    override val partTitle: String = "",
    override val index: Int,
    val nodeId: Long,
    val edgeId: Long? = null,
    /** 节点视频的起始位置（秒），null 表示从头播 */
    val startPos: Int? = null,
    val isCurrent: Boolean = false,
) : VideoListItemData(aid, cid, epid, seasonId, title, index) {
    override val lazyKey: String get() = "node:$aid:$nodeId:$edgeId:$startPos"
}
