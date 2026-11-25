package com.github.mytv.bv.entity

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 刷刷大屏布局规则（平板、折叠屏、双屏共用；设计稿「大屏与折叠」一节）：
 *
 * 1. 先切区：折叠屏半开、双屏跨屏时按折痕 / 铰链切成两片，画面只放第一片（左或上），不跨折痕；
 *    第二片放评论（竖向折痕）或控制（横向折痕）。铰链（occluding）一律留空。
 * 2. 画面贴边：竖屏视频高度顶满所在区域、贴左；横屏视频宽度顶满、贴顶。不居中、不加边距和圆角框。
 * 3. 能铺满就铺满：铺满所需的裁切，竖屏视频不超过 20%、横屏视频不超过 10% 时直接放大铺满，信息叠在画面上。
 * 4. 剩下的空间放内容：右侧剩余宽度 < 300 放竖排操作列，300～720 放信息栏，≥ 720 拆两栏（信息 + 评论）；
 *    下方剩余高度 < 360 放信息条，≥ 360 信息条下面接评论。
 * 5. 评论不盖画面：单区的横屏视频打开评论时，右侧让出 400 给评论，画面按 2、3 条重新贴边。
 *
 * 所有尺寸都是 dp，坐标相对传入的容器（已扣掉导航轨等外壳占用）。纯函数，便于单元测试。
 */
object ShortsLayoutRules {
    const val PORTRAIT_CROP_LIMIT = 0.2f
    const val LANDSCAPE_CROP_LIMIT = 0.1f
    const val ACTIONS_MAX_WIDTH = 300f
    const val TWO_COLUMN_MIN_WIDTH = 720f
    const val STRIP_COMMENTS_MIN_HEIGHT = 360f
    const val COMMENTS_WIDTH = 400f

    /** 单区横屏视频让出评论栏的最小宽度：让出 400 后画面区至少还要 400 */
    const val COMMENTS_SPLIT_MIN_WIDTH = 800f

    /** 折痕两侧都至少这么宽 / 高才分区，否则当作没有折痕 */
    const val MIN_PANE = 240f
}

data class ShortsRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

enum class ShortsZoneKind {
    /** 竖排操作列（右侧剩余宽度 < 300） */
    Actions,

    /** 信息栏：UP 主、标题、操作，下面用标签切换「评论 / 相关 / 设置」 */
    InfoTabs,

    /** 信息栏，不带评论（评论在折痕另一侧） */
    Info,

    /** 两栏：信息 + 评论 */
    InfoAndComments,

    /** 只放评论（折痕另一侧，或横屏视频打开评论时让出的 400） */
    Comments,

    /** 下方信息条 */
    InfoStrip,

    /** 下方信息条 + 评论 */
    InfoStripWithComments,

    /** 折痕另一侧的控制区：大号进度条、上一条 / 播放 / 下一条、操作 */
    Controls,
}

data class ShortsZone(val kind: ShortsZoneKind, val rect: ShortsRect)

/** 折痕 / 铰链，坐标相对容器 */
data class ShortsFold(
    val vertical: Boolean,
    val start: Float,
    val thickness: Float,
    val occluding: Boolean,
)

data class ShortsLayout(
    /** 画面矩形 */
    val video: ShortsRect,
    /** 画面所在区域（切区后的第一片，或整个容器扣掉评论栏） */
    val videoArea: ShortsRect,
    /** true = 画面裁切铺满所在区域，信息叠在画面上 */
    val cover: Boolean,
    /** 铺满所需的裁切比例（0～1），cover 为 false 时没有裁切 */
    val crop: Float,
    /** 画面以外的区域，按摆放顺序 */
    val zones: List<ShortsZone>,
    /** 铰链等不可用区域；没有时为 null */
    val hinge: ShortsRect? = null,
    /** 有效的折痕（已切区） */
    val split: Boolean = false,
) {
    fun zone(kind: ShortsZoneKind): ShortsZone? = zones.firstOrNull { it.kind == kind }
}

/**
 * @param width 容器宽（dp）
 * @param height 容器高（dp）
 * @param fold 容器内的折痕；null 或分出来的某一片太小时不切区
 * @param videoWidth 视频宽，优先用播放器上报的值；0 表示未知
 * @param videoHeight 视频高；0 表示未知（未知时按 16:9 横屏处理，与 [ShortsItem.isPortrait] 一致）
 * @param commentsOpen 评论是否打开（只影响单区的横屏视频）
 */
fun resolveShortsLayout(
    width: Float,
    height: Float,
    fold: ShortsFold?,
    videoWidth: Int,
    videoHeight: Int,
    commentsOpen: Boolean,
): ShortsLayout {
    val w = width.coerceAtLeast(1f)
    val h = height.coerceAtLeast(1f)
    val known = videoWidth > 0 && videoHeight > 0
    val portrait = known && videoHeight > videoWidth
    val ratio = if (known) videoWidth.toFloat() / videoHeight else 16f / 9f

    val panes = fold?.let { splitPanes(w, h, it) }
    if (fold != null && panes != null) {
        val (first, second, hinge) = panes
        val placed = place(first, ratio, portrait)
        val zones = buildList {
            placed.spare?.let { add(spareZone(it, placed.spareRight, commentsElsewhere = true)) }
            add(ShortsZone(if (fold.vertical) ShortsZoneKind.Comments else ShortsZoneKind.Controls, second))
        }
        return ShortsLayout(
            video = placed.video,
            videoArea = first,
            cover = placed.cover,
            crop = placed.crop,
            zones = zones,
            hinge = hinge,
            split = true,
        )
    }

    var area = ShortsRect(0f, 0f, w, h)
    var comments: ShortsRect? = null
    if (!portrait && commentsOpen && w >= ShortsLayoutRules.COMMENTS_SPLIT_MIN_WIDTH) {
        val cw = ShortsLayoutRules.COMMENTS_WIDTH
        comments = ShortsRect(w - cw, 0f, cw, h)
        area = ShortsRect(0f, 0f, w - cw, h)
    }
    val placed = place(area, ratio, portrait)
    val zones = buildList {
        placed.spare?.let { add(spareZone(it, placed.spareRight, commentsElsewhere = comments != null)) }
        comments?.let { add(ShortsZone(ShortsZoneKind.Comments, it)) }
    }
    return ShortsLayout(
        video = placed.video,
        videoArea = area,
        cover = placed.cover,
        crop = placed.crop,
        zones = zones,
    )
}

/** 铺满（cover）所在区域需要裁掉的比例 */
fun shortsCoverCrop(areaWidth: Float, areaHeight: Float, ratio: Float): Float {
    if (areaWidth <= 0f || areaHeight <= 0f || ratio <= 0f) return 0f
    // 按宽、按高两种缩放：铺满取大，完整显示取小
    val byWidth = areaWidth / ratio
    val byHeight = areaHeight
    val coverScale = max(byWidth, byHeight)
    val fitScale = min(byWidth, byHeight)
    return 1f - fitScale / coverScale
}

private data class Placed(
    val video: ShortsRect,
    val cover: Boolean,
    val crop: Float,
    val spare: ShortsRect?,
    val spareRight: Boolean,
)

private fun place(area: ShortsRect, ratio: Float, portrait: Boolean): Placed {
    val crop = shortsCoverCrop(area.width, area.height, ratio)
    val limit = if (portrait) ShortsLayoutRules.PORTRAIT_CROP_LIMIT else ShortsLayoutRules.LANDSCAPE_CROP_LIMIT
    if (crop <= limit) {
        return Placed(area, cover = true, crop = crop, spare = null, spareRight = false)
    }
    val areaRatio = area.width / area.height
    return if (areaRatio > ratio) {
        // 区域比画面宽：高度顶满、贴左，右边剩空
        val vw = (area.height * ratio).roundToInt().toFloat().coerceAtMost(area.width)
        Placed(
            video = ShortsRect(area.x, area.y, vw, area.height),
            cover = false,
            crop = crop,
            spare = ShortsRect(area.x + vw, area.y, area.width - vw, area.height).takeIf { it.width >= 1f },
            spareRight = true,
        )
    } else {
        // 区域比画面窄：宽度顶满、贴顶，下面剩空
        val vh = (area.width / ratio).roundToInt().toFloat().coerceAtMost(area.height)
        Placed(
            video = ShortsRect(area.x, area.y, area.width, vh),
            cover = false,
            crop = crop,
            spare = ShortsRect(area.x, area.y + vh, area.width, area.height - vh).takeIf { it.height >= 1f },
            spareRight = false,
        )
    }
}

private fun spareZone(spare: ShortsRect, right: Boolean, commentsElsewhere: Boolean): ShortsZone {
    val kind = if (right) {
        when {
            spare.width < ShortsLayoutRules.ACTIONS_MAX_WIDTH -> ShortsZoneKind.Actions
            commentsElsewhere -> ShortsZoneKind.Info
            spare.width >= ShortsLayoutRules.TWO_COLUMN_MIN_WIDTH -> ShortsZoneKind.InfoAndComments
            else -> ShortsZoneKind.InfoTabs
        }
    } else {
        if (!commentsElsewhere && spare.height >= ShortsLayoutRules.STRIP_COMMENTS_MIN_HEIGHT) {
            ShortsZoneKind.InfoStripWithComments
        } else {
            ShortsZoneKind.InfoStrip
        }
    }
    return ShortsZone(kind, spare)
}

/** 按折痕切成两片；折痕不在容器内、或任何一片太小时返回 null */
private fun splitPanes(w: Float, h: Float, fold: ShortsFold): Triple<ShortsRect, ShortsRect, ShortsRect?>? {
    val size = if (fold.vertical) w else h
    val start = fold.start
    val end = fold.start + fold.thickness.coerceAtLeast(0f)
    if (start <= 0f || end >= size) return null
    val firstSize = start
    val secondSize = size - end
    if (firstSize < ShortsLayoutRules.MIN_PANE || secondSize < ShortsLayoutRules.MIN_PANE) return null
    return if (fold.vertical) {
        Triple(
            ShortsRect(0f, 0f, firstSize, h),
            ShortsRect(end, 0f, secondSize, h),
            if (fold.occluding && fold.thickness > 0f) ShortsRect(start, 0f, fold.thickness, h) else null,
        )
    } else {
        Triple(
            ShortsRect(0f, 0f, w, firstSize),
            ShortsRect(0f, end, w, secondSize),
            if (fold.occluding && fold.thickness > 0f) ShortsRect(0f, start, w, fold.thickness) else null,
        )
    }
}
