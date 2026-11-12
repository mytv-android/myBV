package com.github.mytv.bv.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.network.LocalNetworkPermission
import com.github.mytv.bv.tv.TvLauncherPublisher
import com.github.mytv.bv.ui.designsystem.theme.BvRoot
import com.github.mytv.bv.ui.feature.accounts.UserLockGate
import com.github.mytv.bv.ui.feature.login.DeepLinkEffect
import com.github.mytv.bv.ui.feature.migration.LegacyMigrationPrompts
import com.github.mytv.bv.ui.feature.settings.LaunchUpdatePrompt
import com.github.mytv.bv.ui.feature.settings.UpdatePromptHost
import com.github.mytv.bv.ui.navigation.AppShell
import com.github.mytv.bv.ui.widget.WidgetAuthObserver
import com.github.mytv.bv.ui.widget.WidgetRefresher
import com.github.mytv.bv.ui.widget.WidgetScheduler
import com.github.mytv.bv.util.CacheManager
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.WidgetSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 唯一启动入口：同时声明 LAUNCHER 与 LEANBACK_LAUNCHER。 */
class MainActivity : ComponentActivity() {
    /** 深链 Intent 用 State 承载，singleTop 热启动时 onNewIntent 也能触发重组。 */
    private var deepLinkIntent by mutableStateOf<Intent?>(null)

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 权限按 UID 生效，BVApp 里常驻的 HttpServer 无需重启即可恢复局域网可达
            Log.i(TAG, "ACCESS_LOCAL_NETWORK granted=$granted")
        }

    private val tvPublisher: TvLauncherPublisher? by lazy {
        runCatching { BVApp.koinApplication.koin.get<TvLauncherPublisher>() }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        var preferencesReady = false
        installSplashScreen().setKeepOnScreenCondition { !preferencesReady }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinkIntent = intent
        WidgetScheduler.ensureScheduled(applicationContext)
        observeWidgetSignals()
        observeThemeChanges()
        // 启动时后台检查缓存阈值（默认「不限制」，只有用户设置上限后才会清理）
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { CacheManager(applicationContext).checkCache() }
        }
        lifecycleScope.launch {
            // 保留启动画面直到真实主题就绪，避免阻塞主线程或先显示错误的主题。
            PreferenceWrites.awaitPending()
            val (initialPrefs, showFps) = withContext(Dispatchers.IO) {
                Prefs.themePrefsFlow.first() to Prefs.showFps
            }
            setContent {
                BvRoot(initialPrefs, initialShowFps = showFps) {
                    WidgetAuthObserver()
                    UserLockGate {
                        val nav = rememberNavController()
                        AppShell(nav)
                        DeepLinkEffect(deepLinkIntent, nav)
                        LegacyMigrationPrompts()
                        LaunchUpdatePrompt()
                        UpdatePromptHost()
                    }
                }
            }
            preferencesReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        // 电视桌面频道：推荐每次启动尝试同步（内部 30 分钟节流），
        // 稍后再看只在用户加过频道且已登录时同步。
        tvPublisher?.let { publisher ->
            lifecycleScope.launch {
                // 推荐先建：应用的第一个频道会自动上桌面；稍后再看作为第二个频道需要用户批准。
                runCatching { publisher.syncRecommend() }
                runCatching { publisher.syncToView() }
            }
        }
        requestLocalNetworkPermissionOnce()
    }

    /**
     * Android 17 的本地网络保护：BVApp 启动即常驻日志下载服务，但同局域网设备扫二维码
     * 时会被拦在本地网络之外（表现是超时，服务端毫无异常）。进首页、界面已经可见时申请一次，
     * 拒绝后不再反复弹窗，由日志页的入口提供再次申请 / 去系统设置的路径。
     */
    private fun requestLocalNetworkPermissionOnce() {
        if (localNetworkPermissionRequested) return
        if (!LocalNetworkPermission.isRequired) return
        if (LocalNetworkPermission.isGranted(this)) return
        localNetworkPermissionRequested = true
        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    override fun onStop() {
        super.onStop()
        // 回到桌面时让小组件拿到刚才在 App 里的改动（稍后再看、历史记录等）
        WidgetRefresher.refreshNow(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntent = intent
    }

    /** 播放进度 / 稍后再看变化：最多一分钟刷新一次继续观看。 */
    private fun observeWidgetSignals() {
        lifecycleScope.launch {
            var last = 0L
            WidgetSignals.changes.collect {
                val now = SystemClock.elapsedRealtime()
                if (now - last >= 60_000L) {
                    last = now
                    WidgetRefresher.requestUserDataChanged(applicationContext)
                }
            }
        }
    }

    /** 主题 / 跟随内容的种子色变了，桌面组件重新取色。 */
    private fun observeThemeChanges() {
        lifecycleScope.launch {
            Prefs.themePrefsFlow
                .drop(1)
                .distinctUntilChanged()
                .debounce(500)
                .collect { WidgetRefresher.updateWidgetsSoon(applicationContext) }
        }
    }

    private companion object {
        const val TAG = "BvMainActivity"

        /** 每个进程只主动申请一次：用户拒绝后反复弹窗只会招致「永久拒绝」。 */
        var localNetworkPermissionRequested = false
    }
}
