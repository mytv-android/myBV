package com.github.mytv.bv.util

import android.graphics.Bitmap
import android.graphics.BitmapDrawable
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 限制 Coil 解码出的位图尺寸（常量与缩放逻辑见 [limitPixels]）。
 *
 * B 站的评论 / 动态配图里出现过 1200×25088 这类超长图：图片预览按原图请求、
 * 或列表偶发不降采样时都会踩到盒子的显示列表上限，这里统一压回去。
 *
 * 只缩放、不回收原图——它可能还在 Coil 内存缓存里，回收会让别处的绘制再崩一次。
 */
class BitmapSizeLimitInterceptor(private val maxPixels: Int = MAX_BITMAP_PIXELS) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed(chain.request)
        if (result !is SuccessResult) return result
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return result
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels <= maxPixels || bitmap.isRecycled) return result

        // 拦截链默认跑在主线程，缩放放到后台，避免大图卡一帧
        val scaled = withContext(Dispatchers.Default) { bitmap.limitPixels(maxPixels) }
        if (scaled === bitmap) return result

        return result.copy(drawable = BitmapDrawable(result.request.context.resources, scaled))
    }
}
