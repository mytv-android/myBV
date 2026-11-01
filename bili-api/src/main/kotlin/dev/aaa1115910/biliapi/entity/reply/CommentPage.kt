package dev.aaa1115910.biliapi.entity.reply

data class CommentPage(
    val nextWebPage: String = "",
    val nextAppPage: String = ""
)

/**
 * 楼中楼分页。Web 接口的 `pn` 从 1 开始计数，默认值必须是首页 1：传 0 接口返回空列表，
 * 且回包 `page.count` 为 0 时 [dev.aaa1115910.biliapi.entity.reply.CommentRepliesData] 的
 * hasNext 会判成 false，加载更多不会再触发，楼中楼就一直空着。
 */
data class CommentReplyPage(
    val nextWebPage: Int = 1,
    val nextAppPage: String = ""
)
