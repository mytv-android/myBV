package com.github.mytv.bv.player

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 媒体请求首包的 `Range` 兜底拦截器。
 *
 * B 站海外 CDN（如 `upos-sz-mirrorcosov.bilivideo.com`）对不带 `Range` 的整段 GET 返回
 * `HTTP 567` 和一个 HTML 页面，带上 `Range: bytes=0-` 则正常返回 206。media3 打开 `.m4s`
 * 的首包 `position = 0`、长度未知，`OkHttpDataSource` 不会自己加 `Range`，于是海外网络下
 * 起播直接报 Source error。
 *
 * 这里只在请求没有 `Range` 时补一个开区间；seek 等场景 media3 已经带了 `Range`，保持原样，
 * 保证同一个请求最多一个 `Range`。HLS 播放列表（`.m3u8`）不是字节流，跳过。
 *
 * 做法参考 [Frost819/newBV](https://github.com/Frost819/newBV) 的同名拦截器（MIT License）。
 */
internal object MediaRangeInterceptor : Interceptor {
    private const val RANGE_HEADER = "Range"
    private const val OPEN_RANGE_VALUE = "bytes=0-"
    private const val HLS_PLAYLIST_SUFFIX = ".m3u8"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val needsRange = request.method == "GET" &&
            request.header(RANGE_HEADER) == null &&
            !request.url.encodedPath.endsWith(HLS_PLAYLIST_SUFFIX, ignoreCase = true)
        if (!needsRange) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(RANGE_HEADER, OPEN_RANGE_VALUE).build()
        )
    }
}
