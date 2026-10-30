package com.github.mytv.bv.tv

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.util.Prefs
import dev.aaa1115910.biliapi.entity.home.RecommendPage
import dev.aaa1115910.biliapi.entity.ugc.UgcItem
import dev.aaa1115910.biliapi.entity.user.ToViewItem
import dev.aaa1115910.biliapi.entity.user.ToViewItemType
import dev.aaa1115910.biliapi.repositories.RecommendVideoRepository
import dev.aaa1115910.biliapi.repositories.ToViewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

private const val MAX_PROGRAMS = 20
private const val RECOMMEND_SYNC_INTERVAL = 30 * 60 * 1000L
private const val TOVIEW_SYNC_INTERVAL = 15 * 60 * 1000L

data class TvChannelStatus(val channelId: Long, val browsable: Boolean)

@Single
class TvLauncherPublisher(
    private val recommendRepository: RecommendVideoRepository,
    private val toViewRepository: ToViewRepository,
) {
    private val context: Context get() = BVApp.context
    private val store = TvChannelsStore()

    suspend fun syncRecommend(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!available()) return@withContext
        if (!force && !store.shouldSync(RecommendSpec.key, RECOMMEND_SYNC_INTERVAL)) {
            return@withContext
        }

        val items = runCatching { fetchRecommend() }.getOrElse { return@withContext }
        if (items.isEmpty()) return@withContext

        if (runCatching { publish(RecommendSpec, items, createIfMissing = true) }
                .getOrDefault(false)) {
            store.touch(RecommendSpec.key)
        }
    }

    suspend fun syncToView(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!available()) return@withContext

        if (!Prefs.isLogin) {
            clearPrograms(ToViewSpec.key)
            return@withContext
        }
        // 用户没点过「添加到电视桌面」就不创建、不同步
        val channelId = store.channel(ToViewSpec.key).channelId
        if (channelId == 0L) return@withContext
        // 用户拒绝批准或后来隐藏了频道：别往不可见的频道里写节目
        if (!force &&
            PreviewChannelHelper(context).getPreviewChannel(channelId)?.isBrowsable != true
        ) {
            return@withContext
        }
        if (!force && !store.shouldSync(ToViewSpec.key, TOVIEW_SYNC_INTERVAL)) {
            return@withContext
        }

        // 空列表也要发布：清空稍后再看时要把桌面上残留的节目删掉
        val items = runCatching { fetchToView() }.getOrElse { return@withContext }

        if (runCatching { publish(ToViewSpec, items, createIfMissing = false) }
                .getOrDefault(false)) {
            store.touch(ToViewSpec.key)
        }
    }

    /** 只创建稍后再看频道行（此时不可见），返回 id 和当前是否已上桌面。 */
    suspend fun ensureToViewChannel(): TvChannelStatus? = withContext(Dispatchers.IO) {
        if (!available() || !Prefs.isLogin) return@withContext null

        runCatching {
            val helper = PreviewChannelHelper(context)
            val existing = helper.getAllChannels()
                .firstOrNull { it.internalProviderId == ToViewSpec.providerId }
            if (existing != null) {
                store.update(ToViewSpec.key) { it.copy(channelId = existing.id) }
                return@runCatching TvChannelStatus(existing.id, existing.isBrowsable)
            }

            val id = helper.publishChannel(buildChannel(ToViewSpec, logo()))
                .takeIf { it > 0 }
                ?: return@runCatching null
            store.update(ToViewSpec.key) { it.copy(channelId = id) }
            TvChannelStatus(id, false)
        }.getOrNull()
    }

    private fun publish(
        spec: TvChannelSpec,
        items: List<TvItem>,
        createIfMissing: Boolean,
    ): Boolean {
        val helper = PreviewChannelHelper(context)
        val channelId = ensureChannel(helper, spec, createIfMissing) ?: run {
            store.update(spec.key) { it.copy(channelId = 0) }
            return false
        }

        val channel = store.channel(spec.key)
        val sameChannel = channel.channelId == channelId
        store.update(spec.key) { it.copy(channelId = channelId) }

        val ids = channel.programs.toMutableMap()
        val hidden = channel.hidden.toMutableSet()
        val alive = mutableSetOf<Long>()

        items.filterNot { it.aid in hidden }.forEachIndexed { index, item ->
            val old = ids[item.aid]
            when {
                old != null && helper.getPreviewProgram(old) != null -> {
                    helper.updatePreviewProgram(old, buildProgram(channelId, spec, item, index))
                    alive += item.aid
                }
                old != null && sameChannel -> {
                    // 节目没了但频道没重建：用户从桌面移除，记墓碑
                    ids.remove(item.aid)
                    hidden += item.aid
                }
                else -> {
                    val newId = helper.publishPreviewProgram(
                        buildProgram(channelId, spec, item, index)
                    )
                    if (newId > 0) {
                        ids[item.aid] = newId
                        alive += item.aid
                    }
                }
            }
        }

        (ids.keys - alive).forEach { aid ->
            ids.remove(aid)?.let { runCatching { helper.deletePreviewProgram(it) } }
        }
        val current = store.channel(spec.key)
        // 同步期间用户可能从桌面移除了节目：墓碑优先，别把它又写回去
        hidden += current.hidden
        current.hidden.forEach { ids.remove(it) }
        val updated = current.copy(programs = ids, hidden = hidden)
        if (updated != current) store.update(spec.key) { updated }
        return true
    }

    private fun ensureChannel(
        helper: PreviewChannelHelper,
        spec: TvChannelSpec,
        createIfMissing: Boolean,
    ): Long? {
        val all = helper.getAllChannels()
        all.firstOrNull { it.internalProviderId == spec.providerId }?.let {
            helper.updatePreviewChannel(it.id, buildChannel(spec))
            if (it.isBrowsable) {
                if (!store.channel(spec.key).wasBrowsable) {
                    store.update(spec.key) { state -> state.copy(wasBrowsable = true) }
                }
            } else if (spec.browsableByDefault && !store.channel(spec.key).wasBrowsable) {
                // 首次发布时没成功上桌面：下次同步再补一次；成功过就不再和用户隐藏行为对抗
                TvContractCompat.requestChannelBrowsable(context, it.id)
            }
            return it.id
        }
        if (!createIfMissing) return null

        val channel = buildChannel(spec, logo())
        val id = if (all.isEmpty()) helper.publishDefaultChannel(channel)
        else helper.publishChannel(channel)
        return id.takeIf { it > 0 }
    }

    private fun buildChannel(spec: TvChannelSpec, logo: Bitmap? = null): PreviewChannel =
        PreviewChannel.Builder()
            .setDisplayName(spec.displayName)
            .setDescription(spec.description)
            .setAppLinkIntentUri(Uri.parse(spec.appLink))
            .setInternalProviderId(spec.providerId)
            .apply { if (logo != null) setLogo(logo) }
            .build()

    private fun buildProgram(
        channelId: Long,
        spec: TvChannelSpec,
        item: TvItem,
        index: Int,
    ): PreviewProgram =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(item.title)
            .setDescription(item.description)
            .setDurationMillis(item.durationSeconds * 1000)
            .apply {
                if (item.progressSeconds > 0) {
                    setLastPlaybackPositionMillis(item.progressSeconds * 1000)
                }
            }
            .setPosterArtUri(Uri.parse(posterUrl(item.cover)))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse("bugvideo://video/${item.aid}"))
            .setInternalProviderId(item.aid.toString())
            .setWeight(spec.maxPrograms - index)
            .build()

    private suspend fun fetchRecommend(): List<TvItem> {
        val out = mutableListOf<TvItem>()
        var page = RecommendPage()
        var pageCount = 0
        while (pageCount < 3) {
            val data = recommendRepository.getRecommendVideos(
                page = page,
                preferApiType = Prefs.apiType,
            )
            out += data.items.map { it.toTvItem() }
                .filter { it.aid > 0 && it.cover.isNotBlank() }
            page = data.nextPage
            pageCount++
            if (out.distinctBy { it.aid }.size >= MAX_PROGRAMS) break
        }
        return out.distinctBy { it.aid }.take(MAX_PROGRAMS)
    }

    private suspend fun fetchToView(): List<TvItem> {
        val data = toViewRepository.getToView(cursor = 0L, preferApiType = Prefs.apiType)
        return data.data
            .filter { it.type == ToViewItemType.Archive } // PGC 先不走，深链解析不可靠
            .map { it.toTvItem() }
            .filter { it.aid > 0 && it.cover.isNotBlank() }
            .distinctBy { it.aid }
            .take(MAX_PROGRAMS)
    }

    private fun clearPrograms(key: String) {
        val helper = PreviewChannelHelper(context)
        store.channel(key).programs.values.forEach {
            runCatching { helper.deletePreviewProgram(it) }
        }
        store.update(key) { it.copy(programs = emptyMap()) }
    }

    private fun logo(): Bitmap =
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(160, 160)

    private fun posterUrl(raw: String): String {
        val https = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
            else -> raw
        }
        return if ('@' in https) https else "$https@480w_270h_1c.webp"
    }

    private fun available(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

    private fun UgcItem.toTvItem() = TvItem(
        aid = aid,
        title = title,
        description = author,
        cover = cover,
        durationSeconds = duration,
    )

    private fun ToViewItem.toTvItem() = TvItem(
        aid = oid,
        title = title,
        description = author,
        cover = cover,
        durationSeconds = duration,
        progressSeconds = progress.takeIf { it > 0 } ?: 0,
    )

    private data class TvItem(
        val aid: Long,
        val title: String,
        val description: String,
        val cover: String,
        val durationSeconds: Int,
        val progressSeconds: Int = 0,
    )

    private data class TvChannelSpec(
        val key: String,
        val displayName: String,
        val description: String,
        val appLink: String,
        val maxPrograms: Int = MAX_PROGRAMS,
        val browsableByDefault: Boolean = false,
    ) {
        val providerId: String get() = "mybv.$key"
    }

    private companion object {
        val RecommendSpec = TvChannelSpec(
            key = "recommend",
            displayName = "B 站推荐",
            description = "根据你的口味推荐",
            appLink = "bugvideo://recommend",
            browsableByDefault = true,
        )

        val ToViewSpec = TvChannelSpec(
            key = "toview",
            displayName = "稍后再看",
            description = "来自 myBV 的稍后再看",
            appLink = "bugvideo://toview",
        )
    }
}
