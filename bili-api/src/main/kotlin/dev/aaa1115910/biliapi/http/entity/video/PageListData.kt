package dev.aaa1115910.biliapi.http.entity.video

import kotlinx.serialization.Serializable

@Serializable
data class PageListItem(
    val cid: Long,
    val page: Int = 0,
    val from: String = "",
    val part: String = "",
    val duration: Int = 0,
    val vid: String = "",
    val weblink: String = "",
    val dimension: PageDimension? = null,
)

@Serializable
data class PageDimension(
    val width: Int = 0,
    val height: Int = 0,
    val rotate: Int = 0,
)
