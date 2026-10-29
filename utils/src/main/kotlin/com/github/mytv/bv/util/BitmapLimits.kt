package com.github.mytv.bv.util

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * 图片单边尺寸上限：4096。
 *
 * 部分电视盒子（Amlogic Android 9）的显示列表上限在 4096×4096 附近，位图超过就抛
 * `Canvas: trying to draw too large bitmap` 直接崩溃，跟设备剩多少内存无关。
 */
const val MAX_IMAGE_DIMENSION: Int = 4096

/** 位图像素总量上限：4096×4096。 */
const val MAX_BITMAP_PIXELS: Int = MAX_IMAGE_DIMENSION * MAX_IMAGE_DIMENSION

/**
 * 位图超过 [MAX_BITMAP_PIXELS] 时等比缩小，否则原样返回。
 *
 * 用于绕开 Coil 的取图路径（弹幕蒙版 SVG / 1bpp 位图、视频快照等）：
 * 这些位图最终都按控件尺寸绘制，缩到上限以内肉眼看不出差别，但能避免整应用崩掉。
 */
fun Bitmap.limitPixels(maxPixels: Int = MAX_BITMAP_PIXELS): Bitmap {
    if (isRecycled) return this
    val pixels = width.toLong() * height
    if (pixels <= maxPixels) return this
    val scale = sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true) }
        .getOrNull() ?: this
}
