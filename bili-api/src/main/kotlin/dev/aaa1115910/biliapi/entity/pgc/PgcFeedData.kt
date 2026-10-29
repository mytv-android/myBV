package dev.aaa1115910.biliapi.entity.pgc

data class PgcFeedData(
    var hasNext: Boolean,
    var cursor: Int,
    var items: List<PgcItem> = emptyList(),
    var ranks: List<FeedRank> = emptyList()
) {
    companion object {
        fun fromPgcFeedData(data: dev.aaa1115910.biliapi.http.entity.pgc.PgcFeedData): PgcFeedData {
            return PgcFeedData(
                hasNext = data.hasNext,
                cursor = data.coursor,
                items = data.items.mapNotNull { subItem ->
                    runCatching { PgcItem.fromFeedSubItem(subItem) }.getOrNull()
                        ?.takeIf { it.seasonId > 0 }
                },
                ranks = emptyList()
            )
        }

        fun fromPgcFeedData(data: dev.aaa1115910.biliapi.http.entity.pgc.PgcFeedV3Data): PgcFeedData {
            // firstOrNull：接口偶尔会下发 sub_items 为空的条目，first 会抛异常把整页推荐带走
            val itemsList = data.items.find { it.subItems.firstOrNull()?.cardStyle == "v_card" }
            val ranksList = data.items.find { it.subItems.firstOrNull()?.cardStyle == "rank" }
            return PgcFeedData(
                hasNext = data.hasNext,
                cursor = data.coursor,
                // 逐条兜底：单条缺字段只跳过该条（没有 seasonId 的条目点了也打不开详情）
                items = itemsList?.subItems?.mapNotNull { subItem ->
                    runCatching { PgcItem.fromFeedSubItem(subItem) }.getOrNull()
                        ?.takeIf { it.seasonId > 0 }
                } ?: emptyList(),
                ranks = ranksList?.subItems?.mapNotNull { subItem ->
                    runCatching { FeedRank.fromFeedSubItem(subItem) }.getOrNull()
                } ?: emptyList()
            )
        }
    }

    data class FeedRank(
        var cover: String,
        var title: String,
        var subTitle: String,
        var items: List<PgcItem>
    ) {
        companion object {
            fun fromFeedSubItem(feedSubItem: dev.aaa1115910.biliapi.http.entity.pgc.PgcFeedV3Data.FeedItem.FeedSubItem): FeedRank {
                return FeedRank(
                    cover = feedSubItem.cover,
                    title = feedSubItem.title,
                    subTitle = feedSubItem.subTitle,
                    items = feedSubItem.subItems?.mapNotNull { subItem ->
                        runCatching { PgcItem.fromFeedSubItem(subItem) }.getOrNull()
                            ?.takeIf { it.seasonId > 0 }
                    } ?: emptyList()
                )
            }
        }
    }
}

