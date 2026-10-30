package com.github.mytv.bv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.tv.TvLauncherPublisher
import com.github.mytv.bv.ui.designsystem.theme.BvRoot
import com.github.mytv.bv.ui.feature.accounts.UserLockGate
import com.github.mytv.bv.ui.feature.login.DeepLinkEffect
import com.github.mytv.bv.ui.feature.migration.LegacyMigrationPrompts
import com.github.mytv.bv.ui.feature.settings.LaunchUpdatePrompt
import com.github.mytv.bv.ui.navigation.AppShell
import com.github.mytv.bv.util.CacheManager
import com.github.mytv.bv.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 唯一启动入口：同时声明 LAUNCHER 与 LEANBACK_LAUNCHER。 */
class MainActivity : ComponentActivity() {
    /** 深链 Intent 用 State 承载，singleTop 热启动时 onNewIntent 也能触发重组。 */
    private var deepLinkIntent by mutableStateOf<Intent?>(null)

    private val tvPublisher: TvLauncherPublisher? by lazy {
        runCatching { BVApp.koinApplication.koin.get<TvLauncherPublisher>() }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        var preferencesReady = false
        installSplashScreen().setKeepOnScreenCondition { !preferencesReady }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinkIntent = intent
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
                    UserLockGate {
                        val nav = rememberNavController()
                        AppShell(nav)
                        DeepLinkEffect(deepLinkIntent, nav)
                        LegacyMigrationPrompts()
                        LaunchUpdatePrompt()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntent = intent
    }
}
