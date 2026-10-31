package com.github.mytv.bv.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 小组件的位图素材。RemoteViews 不支持 clip-path，Material Shapes 的饼干形 / 太阳形 / 四叶形
 * 都先画成 Bitmap；封面、头像也在这里下载并按目标尺寸裁剪，全部按 key 缓存。
 */
internal enum class WidgetShape { Cookie, Sunny, Clover }

/** B 站接口偶尔给 `//i0.hdslb.com/...` 这类无协议的封面地址。 */
internal fun widgetImageUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
    else -> url
}

internal object WidgetImages {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * RemoteViews 单次更新有约 1MB 的上限：位图按 dp 缩放，但最高 2 倍，
     * 大图再按面积压到 400KB 以内。
     */
    private fun densityScale(context: Context, widthDp: Float = 0f, heightDp: Float = 0f): Float {
        val density = context.resources.displayMetrics.density
        val byArea = if (widthDp > 0f && heightDp > 0f) {
            sqrt(400_000.0 / (4.0 * widthDp * heightDp)).toFloat()
        } else {
            2f
        }
        return density.coerceAtMost(2f).coerceAtMost(byArea).coerceAtLeast(1f)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * densityScale(context)).toInt().coerceAtLeast(1)

    private fun coverSize(context: Context, widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val scale = densityScale(context, widthDp, heightDp)
        return ((widthDp * scale).toInt().coerceAtLeast(1)) to
                ((heightDp * scale).toInt().coerceAtLeast(1))
    }

    private fun shapePath(cx: Float, cy: Float, radius: Float, shape: WidgetShape): Path =
        Path().apply {
            val steps = 144
            val radiusDouble = radius.toDouble()
            for (i in 0 until steps) {
                val t = i.toDouble() / steps * 2.0 * Math.PI
                val factor = when (shape) {
                    WidgetShape.Cookie -> .9 + .1 * cos(9.0 * t)
                    WidgetShape.Sunny -> .86 + .14 * cos(8.0 * t)
                    WidgetShape.Clover -> .58 + .42 * sqrt(abs(cos(2.0 * t)))
                }
                val r = factor * radiusDouble
                val angle = t - Math.PI / 2.0
                val x = (cx + cos(angle) * r).toFloat()
                val y = (cy + sin(angle) * r).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

    fun shape(context: Context, sizeDp: Float, shape: WidgetShape, color: Int): Bitmap {
        val size = dp(context, sizeDp)
        val key = "shape|$size|$shape|$color"
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                canvas.drawPath(shapePath(size / 2f, size / 2f, size / 2f, shape), paint)
            }
        }.getOrElse { transparent() }
        cache.put(key, bitmap)
        return bitmap
    }

    private fun transparent(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    /** 头像：按目标尺寸下载后裁成饼干形。 */
    suspend fun avatar(
        context: Context,
        url: String,
        sizeDp: Float,
        shape: WidgetShape = WidgetShape.Cookie,
    ): Bitmap? {
        if (url.isBlank()) return null
        val size = dp(context, sizeDp)
        val key = "avatar|$url|$size|$shape"
        cache.get(key)?.let { return it }
        val source = load(context, url, size, size) ?: return null
        val output = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.clipPath(shapePath(size / 2f, size / 2f, size / 2f, shape))
                canvas.drawBitmap(
                    source,
                    centerCrop(source, size, size),
                    RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
                )
            }
        }.getOrNull() ?: return null
        cache.put(key, output)
        return output
    }

    /**
     * 动态组件的头像组：每个 38dp 的底形里嵌 32dp 头像，相邻头像左移 10dp。
     */
    suspend fun avatarStrip(
        context: Context,
        urls: List<String>,
        backgroundColor: Int,
    ): Bitmap? {
        val avatars = urls.filter { it.isNotBlank() }.distinct().take(3)
        if (avatars.isEmpty()) return null
        val scale = densityScale(context)
        val cell = (38f * scale).toInt().coerceAtLeast(1)
        val step = ((38f - 10f) * scale).toInt().coerceAtLeast(1)
        val width = step * (avatars.size - 1) + cell
        val key = "strip|$width|$cell|$backgroundColor|${avatars.joinToString(",")}"
        cache.get(key)?.let { return it }
        val loaded = coroutineScope {
            avatars.map { url -> async { avatar(context, url, 32f) } }.awaitAll()
        }
        val output = runCatching {
            Bitmap.createBitmap(width, cell, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = backgroundColor
                    style = Paint.Style.FILL
                }
                avatars.forEachIndexed { index, _ ->
                    val cx = index * step + cell / 2f
                    canvas.drawPath(shapePath(cx, cell / 2f, cell / 2f, WidgetShape.Cookie), background)
                }
                loaded.forEachIndexed { index, avatar ->
                    if (avatar != null) {
                        canvas.drawBitmap(
                            avatar,
                            index * step + (cell - avatar.width) / 2f,
                            (cell - avatar.height) / 2f,
                            Paint(Paint.ANTI_ALIAS_FLAG),
                        )
                    }
                }
            }
        }.getOrNull() ?: return null
        cache.put(key, output)
        return output
    }

    /** 普通封面，圆角交给 Glance 的 cornerRadius。 */
    suspend fun cover(context: Context, url: String, widthDp: Float, heightDp: Float): Bitmap? {
        if (url.isBlank()) return null
        val (width, height) = coverSize(context, widthDp, heightDp)
        val key = "cover|$url|$width|$height"
        cache.get(key)?.let { return it }
        val output = crop(context, url, width, height) ?: return null
        cache.put(key, output)
        return output
    }

    /** 刷刷背景：封面 + 上下的黑色渐变遮罩，圆角直接烧进位图。 */
    suspend fun shortsCover(
        context: Context,
        url: String,
        widthDp: Float,
        heightDp: Float,
        cornerDp: Float,
    ): Bitmap? {
        if (url.isBlank()) return null
        val scale = densityScale(context, widthDp, heightDp)
        val width = (widthDp * scale).toInt().coerceAtLeast(1)
        val height = (heightDp * scale).toInt().coerceAtLeast(1)
        val corner = cornerDp * scale
        val key = "shorts|$url|$width|$height|$corner"
        cache.get(key)?.let { return it }
        val source = load(context, url, width, height) ?: return null
        val output = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.clipPath(
                    Path().apply {
                        addRoundRect(
                            RectF(0f, 0f, width.toFloat(), height.toFloat()),
                            corner,
                            corner,
                            Path.Direction.CW,
                        )
                    }
                )
                canvas.drawBitmap(
                    source,
                    centerCrop(source, width, height),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
                )
                drawScrim(canvas, width, height)
            }
        }.getOrNull() ?: return null
        cache.put(key, output)
        return output
    }

    private suspend fun crop(context: Context, url: String, width: Int, height: Int): Bitmap? {
        val source = load(context, url, width, height) ?: return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).drawBitmap(
                    source,
                    centerCrop(source, width, height),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
                )
            }
        }.getOrNull()
    }

    private suspend fun load(context: Context, url: String, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            // 组件首次渲染有时间限制：图片 3 秒内没到就先不画，下一次更新再用缓存
            withTimeoutOrNull(3_000) {
                val request = ImageRequest.Builder(context)
                    .data(widgetImageUrl(url))
                    .size(width, height)
                    .allowHardware(false)
                    .build()
                val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                drawable?.let { runCatching { it.toBitmap() }.getOrNull() }
            }
        }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Rect {
        val sourceRatio = source.width.toFloat() / source.height
        val targetRatio = width.toFloat() / height
        return if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).toInt().coerceAtLeast(1)
            val left = (source.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetRatio).toInt().coerceAtLeast(1)
            val top = (source.height - cropHeight) / 2
            Rect(0, top, source.width, top + cropHeight)
        }
    }

    private fun drawScrim(canvas: Canvas, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(
            0f,
            0f,
            w,
            h * .32f,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h * .32f,
                    0x52000000, 0x00000000,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawRect(
            0f,
            h * .42f,
            w,
            h,
            Paint().apply {
                shader = LinearGradient(
                    0f, h * .42f, 0f, h,
                    0x00000000, 0xCC000000.toInt(),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }
}
