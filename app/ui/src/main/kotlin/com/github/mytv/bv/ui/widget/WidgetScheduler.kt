package com.github.mytv.bv.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.mytv.bv.repository.UserRepository
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

/** 周期刷新：稍后再看、动态等按 30 分钟拉一次。 */
internal class WidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { WidgetRefresher.refreshAll(applicationContext, forceData = true) }
        return Result.success()
    }
}

/** 番剧到点更新：单次任务，把「22:30」换成「已更新」。 */
internal class TimelineTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { WidgetRefresher.refreshTimelineAndUpdate(applicationContext) }
        return Result.success()
    }
}

internal object WidgetScheduler {
    private const val WORK_NAME = "bv_widget_refresh"
    private const val TIMELINE_WORK_NAME = "bv_widget_timeline_tick"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** @param atMillis 下一部番剧的更新时间（毫秒时间戳），到点触发一次时间表刷新。 */
    fun scheduleTimelineTick(context: Context, atMillis: Long?) {
        if (atMillis == null) return
        val delay = (atMillis - System.currentTimeMillis() + 60_000L).coerceAtLeast(60_000L)
        val request = OneTimeWorkRequestBuilder<TimelineTickWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        runCatching {
            // 每个目标时间一个唯一任务：重复刷新只会保留一条，到点任务自己再排下一集也不会取消自己
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${TIMELINE_WORK_NAME}_$atMillis",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** 登录 / 登出后让需要账号数据的组件立刻改状态。 */
@Composable
internal fun WidgetAuthObserver() {
    val userRepository: UserRepository = koinInject()
    val context = LocalContext.current
    val login = userRepository.isLogin
    val uid = userRepository.uid
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(login, uid) {
        if (first) {
            first = false
            WidgetRefresher.request(context)
        } else {
            WidgetRefresher.refreshNow(context)
        }
    }
}
