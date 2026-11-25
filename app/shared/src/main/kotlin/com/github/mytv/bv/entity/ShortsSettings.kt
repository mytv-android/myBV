package com.github.mytv.bv.entity

/** 刷刷「播完后」：循环本条（原来的行为）或自动下一条 */
enum class ShortsAfterEnd { Loop, Next }

/**
 * 刷刷弹幕显示区域（只管刷刷，主播放器的区域设置不受影响）。
 * 竖屏画面的构图和横屏不同，所以单独记。
 */
enum class ShortsDanmakuArea(
    /** 占弹幕可用区域高度的比例 */
    val fraction: Float,
) {
    /** 少：约 5 行，只在画面上沿 */
    Few(0.25f),

    /** 半屏 */
    Half(0.5f),

    /** 全屏 */
    Full(1f),
}
