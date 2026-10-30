package com.github.mytv.bv.tv

import com.github.mytv.bv.util.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TvChannelState(
    val channelId: Long = 0,
    val programs: Map<Long, Long> = emptyMap(),
    val hidden: Set<Long> = emptySet(),
    val lastSync: Long = 0,
    val wasBrowsable: Boolean = false,
)

@Serializable
data class TvChannelsState(
    val channels: Map<String, TvChannelState> = emptyMap(),
)

class TvChannelsStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun channel(key: String): TvChannelState = state().channels[key] ?: TvChannelState()

    fun update(key: String, transform: (TvChannelState) -> TvChannelState) {
        synchronized(lock) {
            val current = state()
            val next = transform(current.channels[key] ?: TvChannelState())
            Prefs.tvChannelsState = json.encodeToString(
                current.copy(channels = current.channels + (key to next))
            )
        }
    }

    fun touch(key: String) = update(key) { it.copy(lastSync = System.currentTimeMillis()) }

    fun shouldSync(key: String, intervalMs: Long): Boolean =
        System.currentTimeMillis() - channel(key).lastSync >= intervalMs

    /** 广播只带 programId，遍历所有频道找归属。 */
    fun markProgramRemoved(programId: Long) {
        synchronized(lock) {
            val current = state()
            val channels = current.channels.mapValues { (_, channel) ->
                val avid = channel.programs.entries.firstOrNull { it.value == programId }?.key
                    ?: return@mapValues channel
                channel.copy(programs = channel.programs - avid, hidden = channel.hidden + avid)
            }
            Prefs.tvChannelsState = json.encodeToString(current.copy(channels = channels))
        }
    }

    private fun state(): TvChannelsState =
        runCatching { json.decodeFromString<TvChannelsState>(Prefs.tvChannelsState) }
            .getOrDefault(TvChannelsState())

    private companion object {
        val lock = Any()
    }
}
