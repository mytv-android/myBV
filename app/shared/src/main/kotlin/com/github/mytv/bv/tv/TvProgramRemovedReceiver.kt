package com.github.mytv.bv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 用户从电视桌面移除节目：删除本地映射并记墓碑，后续同步不再插入。 */
class TvProgramRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED) return
        val programId = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
        if (programId <= 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { PreviewChannelHelper(context).deletePreviewProgram(programId) }
                runCatching { TvChannelsStore().markProgramRemoved(programId) }
            } finally {
                pending.finish()
            }
        }
    }
}
