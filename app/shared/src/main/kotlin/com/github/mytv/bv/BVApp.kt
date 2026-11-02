package com.github.mytv.bv

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.WebViewCompat
import androidx.work.Configuration
import coil.Coil
import coil.ImageLoader
import de.schnettler.datastore.manager.DataStoreManager
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliHttpProxyApi
import dev.aaa1115910.biliapi.http.util.BiliAppConf
import dev.aaa1115910.biliapi.http.util.BiliDns
import dev.aaa1115910.biliapi.http.util.BiliWebConf
import dev.aaa1115910.biliapi.http.util.WebCookieManager
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.biliapi.repositories.BiliApiModule
import dev.aaa1115910.biliapi.repositories.ChannelRepository
import com.github.mytv.bv.dao.AppDatabase
import com.github.mytv.bv.entity.AuthData
import com.github.mytv.bv.entity.db.UserDB
import com.github.mytv.bv.migration.LegacyDataMigrator
import com.github.mytv.bv.network.HttpServer
import com.github.mytv.bv.player.PlayerHttpClient
import com.github.mytv.bv.repository.UserRepository
import com.github.mytv.bv.util.BitmapSizeLimitInterceptor
import com.github.mytv.bv.util.BlacklistUtil
import com.github.mytv.bv.util.FirebaseUtil
import com.github.mytv.bv.util.LogCatcherUtil
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.ksp.generated.module
import org.slf4j.impl.HandroidLoggerAdapter
import java.io.File

class BVApp : Application(), Configuration.Provider {
    companion object {
        private const val TAG = "BVApp"

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
        lateinit var dataStoreManager: DataStoreManager
        lateinit var koinApplication: KoinApplication
        var instance: BVApp? = null

        fun getAppDatabase(context: Context = this.context) = AppDatabase.getDatabase(context)
    }

    /**
     * WorkManager 的自动初始化已在清单里关掉，改由这里按需提供配置；
     * 首次 `WorkManager.getInstance()` 时用默认配置初始化。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /**
     * 系统可能在解锁前为了安装 directBootAware 的提供者（Firebase、Room 等）就把本进程拉起来，
     * 此时整个进程跑在设备加密（DE）存储上下文里，并且解锁后也不会自动切回 CE。
     * 之前遇到这种情况直接结束进程，结果是被系统反复拉起：用户只看到永远停在启动页，
     * 日志里连一条异常都没有。解锁后直接换成 CE 上下文，进程就能正常跑完启动流程。
     */
    override fun attachBaseContext(base: Context) {
        val effective = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            base.isDeviceProtectedStorage && UserManager.get(base).isUserUnlocked
        ) {
            Log.w(TAG, "process started in device-protected storage, switching to CE")
            base.createCredentialProtectedStorageContext()
        } else {
            base
        }
        super.attachBaseContext(effective)
    }

    override fun onCreate() {
        super.onCreate()
        // 仍停留在 DE 上下文说明用户尚未解锁：CE 里的 DataStore / Room 都读不到，
        // 应用也没有需要解锁前运行的业务。这里结束进程让系统在解锁后重新拉起；
        // 必须留日志，否则线上只能看到「卡在启动页」，没有任何可排查线索。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDeviceProtectedStorage) {
            Log.w(TAG, "process started before user unlock, exiting")
            Process.killProcess(Process.myPid())
            return
        }
        context = this.applicationContext
        // 迁移 Provider 跑在独立进程，只需要读写本应用数据文件，不能执行整套启动流程
        if (isMigrationProcess()) return
        // 必须在 Koin/UserRepository 打开 Room 之前把旧数据库就位
        LegacyDataMigrator.prepareDatabase(this)
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        dataStoreManager = DataStoreManager(applicationContext.dataStore)
        // 仅 IPv4 开关要在构建 gRPC 通道 / 预热 HTTP client 之前就位
        BiliAppConf.ipv4Only = Prefs.ipv4Only
        PlayerHttpClient.ipv4Only = Prefs.ipv4Only
        initImageLoader()
        if (Prefs.blacklistUser) {
            R.string.blacklist_user_toast.toast(context)
            return
        }
        koinApplication = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@BVApp)
            modules(AppModule().module)
        }
        initFirebase()
        LogCatcherUtil.installLogCatcher()
        initApiConfig()
        initRepository()
        initProxy()
        warmUpApi()
        instance = this
        updateMigration()
        HttpServer.startServer()
        updateBlacklist()
    }

    /** 迁移 Provider 使用 :migration 进程，启动时跳过所有网络/服务初始化。 */
    private fun isMigrationProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().substringBefore('\u0000').endsWith(":migration")
    }.getOrDefault(false)

    /**
     * 全局 Coil ImageLoader：封面等图片到达时 150ms 淡入（内存缓存命中不会重复淡入），
     * 避免图片突兀跳出造成「应用反应慢」的观感。内存 / 磁盘缓存都用 Coil 默认策略。
     * OkHttpClient 走 [BiliDns]，仅 IPv4 开关对图片加载同样生效。
     */
    private fun initImageLoader() {
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient {
                    OkHttpClient.Builder()
                        .dns(BiliDns)
                        .build()
                }
                // 超长图（评论/动态配图）按原图解码会超过部分盒子的显示列表上限，
                // 绘制时抛 "Canvas: trying to draw too large bitmap" 直接崩，统一压到上限内
                .components {
                    add(BitmapSizeLimitInterceptor())
                }
                .crossfade(150)
                .build()
        )
    }

    private fun initFirebase() {
        FirebaseUtil.init(applicationContext)
        when (BuildConfig.BUILD_TYPE) {
            "debug" -> {}
            else -> FirebaseUtil.setCrashlyticsCollectionEnabled(Prefs.enableFirebaseCollection)
        }
    }

    private fun initApiConfig() {
        BiliAppConf.osVersion = Build.VERSION.RELEASE
        BiliAppConf.model = Build.MODEL
        BiliWebConf.webViewVersion = runCatching {
            WebViewCompat.getCurrentLoadedWebViewPackage()!!.versionName!!
                .substringBefore(".").toInt()
        }.getOrDefault(144)
        initWebCookieManager()
    }

    private fun initWebCookieManager() {
        WebCookieManager.refreshTokenProvider = { Prefs.refreshToken }
        WebCookieManager.midProvider = { Prefs.uid }
        WebCookieManager.cookieGetter = { name ->
            // buvid3 在引入 Web Cookie 之前就由 Prefs 生成并用于请求头，
            // 首次运行时用它兜底，保证与 finger/spi 返回的 buvid4 能配对
            Prefs.getWebCookie(name) ?: if (name == "buvid3") Prefs.buvid3 else null
        }
        WebCookieManager.onCookiesUpdated = { updated ->
            // 账号级 cookie 交给 UserRepository 落盘，写进 webExtraCookies 会在登出后继续被注入
            Prefs.setWebCookies(updated.filterKeys { it !in UserRepository.AUTH_COOKIE_NAMES })
            // 请求头里用的 buvid3 必须与 buvid4 同源
            updated["buvid3"]?.takeIf { it.isNotBlank() }?.let { buvid3 ->
                Prefs.buvid3 = buvid3
                runCatching { koinApplication.koin.get<AuthRepository>().buvid3 = buvid3 }
            }
            val authUpdated = updated.filterKeys { it in UserRepository.AUTH_COOKIE_NAMES }
            if (authUpdated.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        koinApplication.koin.get<UserRepository>()
                            .applyWebCookieRefresh(authUpdated)
                    }
                }
            }
        }
    }

    fun initRepository() {
        val channelRepository by koinApplication.koin.inject<ChannelRepository>()
        channelRepository.initDefaultChannel(Prefs.accessToken, Prefs.buvid)

        val authRepository by koinApplication.koin.inject<AuthRepository>()
        authRepository.sessionData = Prefs.sessData.takeIf { it.isNotEmpty() }
        authRepository.biliJct = Prefs.biliJct.takeIf { it.isNotEmpty() }
        authRepository.dedeUserIDCkMd5 = Prefs.uidCkMd5.takeIf { it.isNotEmpty() }
        authRepository.sid = Prefs.sid.takeIf { it.isNotEmpty() }
        authRepository.accessToken = Prefs.accessToken.takeIf { it.isNotEmpty() }
        authRepository.mid = Prefs.uid.takeIf { it != 0L }
        authRepository.buvid3 = Prefs.buvid3
        authRepository.buvid = Prefs.buvid
        authRepository.bindWebCookieManager()
    }

    fun initProxy() {
        if (Prefs.enableProxy) {
            BiliHttpProxyApi.createClient(Prefs.proxyHttpServer)

            val channelRepository by koinApplication.koin.inject<ChannelRepository>()
            runCatching {
                channelRepository.initProxyChannel(
                    Prefs.accessToken,
                    Prefs.buvid,
                    Prefs.proxyGRPCServer
                )
            }
        }
    }

    private fun warmUpApi() {
        CoroutineScope(Dispatchers.IO).launch {
            // 无网络或接口报错时保持静默，不能影响启动；
            // cookie 维护涉及多次网络请求，单独并发，避免拖慢后面的预热
            launch { runCatching { WebCookieManager.initialize() } }
            BiliHttpApi.warmUpClient()
            PlayerHttpClient.preheat(this@BVApp)
        }
    }

    private fun updateMigration() {
        val lastVersionCode = Prefs.lastVersionCode
        if (lastVersionCode >= BuildConfig.VERSION_CODE) return
        Log.i("BVApp", "updateMigration from $lastVersionCode")
        if (lastVersionCode < 576) {
            // 从 Prefs 中读取登录数据写入 UserDB
            if (Prefs.isLogin) {
                runBlocking {
                    val existedUser = getAppDatabase().userDao().findUserByUid(Prefs.uid)
                    if (existedUser == null) {
                        val user = UserDB(
                            uid = Prefs.uid,
                            username = "Unknown",
                            avatar = "",
                            auth = AuthData.fromPrefs().toJson()
                        )
                        getAppDatabase().userDao().insert(user)
                    }
                }
            }
        }
        Prefs.lastVersionCode = BuildConfig.VERSION_CODE
    }

    private fun updateBlacklist() {
        CoroutineScope(Dispatchers.IO).launch {
            BlacklistUtil.updateBlacklist(context)
            BlacklistUtil.checkUid(Prefs.uid)
        }
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "Settings")

@Module(includes = [BiliApiModule::class])
@ComponentScan
class AppModule
