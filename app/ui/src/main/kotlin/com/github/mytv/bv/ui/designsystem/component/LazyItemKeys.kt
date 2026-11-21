package com.github.mytv.bv.ui.designsystem.component

/**
 * LazyColumn / LazyRow / LazyVerticalGrid 的 item key 必须唯一，而接口不保证这一点：
 * 同一个 aid / cid / seasonId 会被重复下发，占位项的 id 还是 0 / null / 空串。
 * 重复 key 会直接抛 IllegalArgumentException（「Key "0" was already used」）把界面带走
 * （1.log：番剧详情 800+ 集，进页即崩）。
 *
 * 所以下标一律拼进 key：id 照原样保留（便于认出这一项），下标保证唯一——只判空不够，
 * 服务端把同一个 id 发两遍时同样会崩。追加加载不会改变已有项的下标，焦点 / 滚动状态不受影响。
 */
internal fun safeLazyKey(id: Long, index: Int): String = "$index-$id"

internal fun safeLazyKey(id: Int, index: Int): String = "$index-$id"

internal fun safeLazyKey(id: String?, index: Int): String = "$index-${id.orEmpty()}"

/** 多段拼成的 key（业务类型 + id、番剧标记 + seasonId 之类）。 */
internal fun safeLazyKey(index: Int, vararg parts: Any?): String =
    "$index-" + parts.joinToString("-") { it?.toString().orEmpty() }
