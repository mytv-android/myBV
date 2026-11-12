package com.github.mytv.bv.ui.feature.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.github.mytv.bv.BuildConfig
import com.github.mytv.bv.migration.LegacyMigrationContract
import com.github.mytv.bv.network.GithubApi
import com.github.mytv.bv.network.entity.Release
import com.github.mytv.bv.ui.PreferenceWrites
import com.github.mytv.bv.util.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.content.ProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

private val updateLogger = KotlinLogging.logger("BvUpdateManager")

/** 授权页跳转次数上限：授权页打得开但固件不认这个开关时，防止「回前台 → 重试 → 再跳授权页」死循环。 */
internal const val MAX_SETTINGS_ATTEMPTS = 2

/** 手动安装时导出 APK 的目录名（Android 10+ 是公共下载目录下的子目录，更老的系统退到外部应用目录）。 */
private const val MANUAL_INSTALL_DIR = "update"

/** 检查更新、下载、安装的状态机。 */
internal enum class UpdateStatus {
    Idle, Checking, Ready, Downloading, Installing,
    NoAvailableUpdate, CheckError, DownloadError, InstallError,
}

/**
 * 更新下载与安装的全部状态。放在 [UpdateManager] 里而不是 Composable 里，
 * 是因为下载要在用户离开提示弹窗之后继续跑——弹窗和 pill 只是这份状态的两个视图。
 *
 * @param downloadProgress 0..1 的下载进度，未知时为 null（首字节还没到）。
 * @param awaitingPermission 已跳去「安装未知应用」授权页，等用户从设置页回来再重试。
 */
internal data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Idle,
    val release: Release? = null,
    val downloadProgress: Float? = null,
    val errorDetail: String? = null,
    val awaitingPermission: Boolean = false,
    val settingsAttempts: Int = 0,
    /** 启动时的静默检查：检查中不弹卡片，只有发现新版本才把状态推成 Ready。 */
    val silent: Boolean = false,
) {
    val busy: Boolean get() = status == UpdateStatus.Downloading || status == UpdateStatus.Installing
    val showPill: Boolean get() = status == UpdateStatus.Downloading

    /**
     * 提示卡是否应该出现。下载中永远不出现——这时由 pill 接替；其余需要用户看见结果 / 回答的状态
     * （检查中、有更新、各种错误、安装中 / 安装权限）都出现。
     */
    val cardUp: Boolean get() = when (status) {
        UpdateStatus.Idle, UpdateStatus.Downloading -> false
        UpdateStatus.Checking -> !silent
        else -> true
    }
}

/**
 * 进程级更新管理器：AndroidManifest 里的 UpdateInstallReceiver 用它查询安装结果，
 * Compose 侧通过 [state] 订阅。
 *
 * 生命周期跟进程走：用户点了「立即更新」之后可以离开设置页、继续看视频，
 * 下载和随后的安装器拉起都不依赖某个 Composable 还活着。
 */
internal object UpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 正在下载的协程；用户取消时取消它，文件交给 CacheManager 的阈值清理。 */
    private var downloadJob: Job? = null

    /** 已经交给系统安装器、等它返回的 APK。 */
    private var installingApk: File? = null
    private var awaitingInstall = false


    /**
     * 检查更新；[silent] 为 true 时只有存在更高版本才会把状态推到 Ready
     * （启动时静默检查用，避免把「没有更新」也当成一次要展示的结果）。
     *
     * 下载 / 安装中直接忽略：这时状态归下载流程所有，覆盖成 Checking 会让 pill 消失、
     * 还会在下载没结束的时候弹出一张结果卡。
     */
    fun check(context: Context, silent: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(status = UpdateStatus.Checking, errorDetail = null, silent = silent) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PreferenceWrites.awaitPending()
                    val latest = GithubApi.getLatestBuild()
                    val revision = latest.revision
                        ?: throw IllegalStateException("无法从发布信息中解析版本号")
                    latest to updateAvailable(context, revision)
                }
            }
            result
                .onSuccess { (latest, hasUpdate) ->
                    updateLogger.info { "Find latest version ${latest.name}" }
                    _state.update {
                        it.copy(
                            release = latest,
                            silent = false,
                            status = if (hasUpdate) {
                                UpdateStatus.Ready
                            } else if (silent) {
                                UpdateStatus.Idle
                            } else {
                                UpdateStatus.NoAvailableUpdate
                            },
                        )
                    }
                }
                .onFailure {
                    updateLogger.warn(it) { "Failed to get latest version" }
                    if (silent) {
                        _state.update { it.copy(status = UpdateStatus.Idle, silent = false) }
                    } else {
                        _state.update { s ->
                            s.copy(
                                status = UpdateStatus.CheckError,
                                errorDetail = it.message ?: it::class.simpleName,
                                silent = false,
                            )
                        }
                    }
                }
        }
    }

    /** 用户接受更新：开始后台下载。已经在下载时忽略，避免点两下起两个任务。 */
    fun download(context: Context, release: Release? = _state.value.release) {
        val latest = release ?: return
        if (_state.value.status == UpdateStatus.Downloading) return
        downloadJob?.cancel()
        _state.update {
            it.copy(
                release = latest,
                status = UpdateStatus.Downloading,
                errorDetail = null,
                downloadProgress = null,
                awaitingPermission = false,
                settingsAttempts = 0,
                silent = false,
            )
        }
        downloadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, CacheManager.UPDATE_CACHE_DIR)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "${UUID.randomUUID()}.apk")
                    file.createNewFile()
                    GithubApi.downloadUpdate(latest, file, progressListener())
                    file
                }
            }
            result.onSuccess { file ->
                // 下载完成后校验包名/版本/签名，再交给安装链
                val blocker = withContext(Dispatchers.IO) {
                    apkTargetBlocker(context, file)
                        ?: if (signatureMatches(context, file)) {
                            null
                        } else {
                            "更新包签名与当前应用不一致，无法覆盖安装"
                        }
                }
                if (blocker != null) {
                    _state.update {
                        it.copy(status = UpdateStatus.InstallError, errorDetail = blocker, downloadProgress = null)
                    }
                    return@onSuccess
                }
                // 缓存写入后检查阈值；preserve 保护刚下载的安装包不被清理
                withContext(Dispatchers.IO) {
                    runCatching { CacheManager(context).checkCache(preserve = file) }
                }
                _state.update { it.copy(status = UpdateStatus.Installing, downloadProgress = null) }
                installingApk = file
                if (installApk(context, file)) {
                    awaitingInstall = true
                } else {
                    onInstallChainFailed(context, file)
                }
            }.onFailure {
                // 用户主动取消（离开 App / 取消下载）不算失败
                if (it is CancellationException) return@onFailure
                updateLogger.warn(it) { "Failed to download update" }
                _state.update { s ->
                    s.copy(
                        status = UpdateStatus.DownloadError,
                        errorDetail = it.message ?: it::class.simpleName,
                    )
                }
            }
        }
    }

    /** 下载中的真实进度：Ktor 的网络分块可能非常频繁，UI 最多 10Hz 更新一次。 */
    private fun progressListener() = object : ProgressListener {
        private var lastUiUpdate = 0L

        override suspend fun onProgress(bytesSentTotal: Long, contentLength: Long?) {
            val now = SystemClock.elapsedRealtime()
            val finished = contentLength != null && bytesSentTotal >= contentLength
            if (!finished && now - lastUiUpdate < 100L) return
            lastUiUpdate = now
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        downloadProgress = if (contentLength != null && contentLength > 0) {
                            (bytesSentTotal.toFloat() / contentLength).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /** 用户关掉提示卡：回到 Idle，本进程不再自动弹出；下载中的话状态不受影响。 */
    fun dismissPrompt() = _state.update { it.copy(status = UpdateStatus.Idle, silent = false) }

    /**
     * 系统安装器是另一个应用：装上、取消之后本页只会 resume，不会重建。
     * 回到前台时确认结果——装好了就关掉弹窗；从授权页回来则无条件重试一次安装，
     * 仍装不上才按次数上限转手动安装。
     */
    fun onResume(context: Context) {
        val apk = installingApk ?: return
        val fromSettings = _state.value.awaitingPermission
        if (!awaitingInstall && !fromSettings) return
        awaitingInstall = false
        installingApk = apk
        _state.update { it.copy(awaitingPermission = false) }
        scope.launch {
            var installed = withContext(Dispatchers.IO) { installSucceeded(context, apk) }
            if (!installed) {
                // 安装器可能比 PackageManager 提交稍早返回，稍等一下再判一次
                delay(1500)
                installed = withContext(Dispatchers.IO) { installSucceeded(context, apk) }
            }
            if (installed) {
                // 回到 Idle，提示卡随之收起
                finishInstalled()
                return@launch
            }
            // 授权页回来不重新判断权限（canRequestPackageInstalls 在盒子上恒 false，
            // 问它等于白问），直接再走一遍安装链
            if (fromSettings) {
                if (installApk(context, apk)) {
                    awaitingInstall = true
                } else {
                    onInstallChainFailed(context, apk)
                }
                return@launch
            }
            _state.update {
                it.copy(
                    status = UpdateStatus.InstallError,
                    errorDetail = context.getString(com.github.mytv.bv.appui.R.string.settings_update_install_cancelled),
                )
            }
        }
    }

    /**
     * 清空状态。安装成功时调用：此时下载已经结束，[downloadJob] 的 cancel 是空操作，
     * 同时也兜住「用户中途取消下载」这条路径。
     */
    private fun finishInstalled() {
        downloadJob?.cancel()
        downloadJob = null
        installingApk = null
        awaitingInstall = false
        _state.value = UpdateUiState()
    }

    /**
     * 三级安装兜底全灭后的退路：有额度就跳「安装未知应用」授权页（用户回来再试），
     * 跳不了或已到上限就把 APK 导到外部目录提示手动安装——不留「无法打开设置」的死路。
     */
    private suspend fun onInstallChainFailed(context: Context, apk: File) {
        val attempts = _state.value.settingsAttempts
        if (attempts < MAX_SETTINGS_ATTEMPTS && openUnknownSourcesSettings(context)) {
            _state.update {
                it.copy(
                    settingsAttempts = attempts + 1,
                    awaitingPermission = true,
                    status = UpdateStatus.Installing,
                )
            }
        } else {
            val exported = withContext(Dispatchers.IO) { exportApkForManualInstall(context, apk) }
            val detail = if (exported != null) {
                context.getString(com.github.mytv.bv.appui.R.string.settings_update_install_manual, exported)
            } else {
                context.getString(com.github.mytv.bv.appui.R.string.settings_update_install_export_failed)
            }
            _state.update {
                it.copy(status = UpdateStatus.InstallError, awaitingPermission = false, errorDetail = detail)
            }
        }
    }
}

/** 安装器 Intent：clipData 和 setData 都要给，部分 ROM 的安装器只读其中一个，漏一个就静默失败。 */
private fun installIntent(uri: Uri, action: String) = Intent(action).apply {
    addCategory(Intent.CATEGORY_DEFAULT)
    setDataAndType(uri, "application/vnd.android.package-archive")
    clipData = ClipData.newRawUri("", uri)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * 二级 Intent 兜底拉起系统安装器：ACTION_VIEW → ACTION_INSTALL_PACKAGE（部分老 ROM 只认后者）。
 * 有没有安装权限是安装器自己的事：这里只负责拉起来，让 startActivity 的结果说话。
 */
private fun startInstallerActivity(context: Context, file: File): Boolean {
    // 必须用实际 applicationId（debug 包带 .debug 后缀），BuildConfig.APPLICATION_ID 是 app:shared 里写死的
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    return listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE).any { action ->
        runCatching { context.startActivity(installIntent(uri, action)) }
            .onFailure { updateLogger.warn(it) { "No activity handled install intent: $action" } }
            .isSuccess
    }
}

/** 会话安装结果广播的 action，与 manifest 里 intent-filter 的 ${applicationId} 前缀保持一致。 */
private fun installStatusAction(context: Context) = "${context.packageName}.action.UPDATE_INSTALL_STATUS"

/** 会话安装的结果回调：系统要往 Intent 里写 EXTRA_STATUS，PendingIntent 必须是 MUTABLE。 */
private fun statusIntentSender(context: Context): IntentSender {
    val intent = Intent(context, UpdateInstallReceiver::class.java)
        .setAction(installStatusAction(context))
    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        .intentSender
}

/**
 * 三级兜底：PackageInstaller 会话安装，不依赖任何安装器 Activity 的 IntentFilter，
 * 电视盒子上「没有应用能响应安装 Intent」时仍有机会装上。写 APK 走 IO。
 */
private suspend fun installViaSession(context: Context, file: File): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setSize(file.length()) }
            installer.openSession(installer.createSession(params)).use { session ->
                session.openWrite(file.name, 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    // fsync 要 API 24+，minSdk 23 的设备只能用 flush
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) session.fsync(output)
                }
                session.commit(statusIntentSender(context))
            }
        }
            .onFailure { updateLogger.warn(it) { "PackageInstaller session install failed" } }
            .isSuccess
    }

/**
 * 多级兜底拉起系统安装器：ACTION_VIEW → ACTION_INSTALL_PACKAGE → PackageInstaller 会话。
 * 任意一级成功即返回 true，全灭才轮到授权页 / 手动安装。
 */
private suspend fun installApk(context: Context, file: File): Boolean {
    if (startInstallerActivity(context, file)) return true
    return installViaSession(context, file)
}

/**
 * 「安装未知应用」授权页逐级降级：Android 8 前是安全设置里的全局开关，之后是
 * 带包名的专页 → 全局页 → 应用详情页（开关在详情页里）。
 * 电视盒子上这些页面经常一个都没有，返回 false 让调用方转手动安装。
 */
private fun openUnknownSourcesSettings(context: Context): Boolean {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = listOfNotNull(
        // Android 8 之前没有按应用授予的「安装未知应用」，全局开关在安全设置里
        Intent(Settings.ACTION_SECURITY_SETTINGS).takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.O },
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    return intents.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { updateLogger.warn(it) { "No activity handled settings intent: ${intent.action}" } }
            .isSuccess
    }
}

/**
 * 授权页全打不开：把已下载并校验过的 APK 复制到用户够得着的地方，让文件管理器 / U 盘能装上。
 * 返回给界面看的路径，null 表示导出失败。
 */
private fun exportApkForManualInstall(context: Context, apk: File): String? = runCatching {
    exportToDownloads(context, apk)?.let { return@runCatching it }
    val dir = File(context.getExternalFilesDir(null), MANUAL_INSTALL_DIR)
    if (!dir.exists()) dir.mkdirs()
    val target = File(dir, apk.name)
    apk.copyTo(target, overwrite = true)
    target.absolutePath
}.getOrNull()

/**
 * Android 10+：写进公共「下载」目录（MediaStore），不需要存储权限，文件管理器直接能看到。
 * 返回形如 Download/update/xxx.apk 的展示路径；更低版本或写失败返回 null。
 */
private fun exportToDownloads(context: Context, apk: File): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$MANUAL_INSTALL_DIR"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { output ->
            apk.inputStream().use { input -> input.copyTo(output) }
        } ?: return@runCatching null
        // IS_PENDING 归零之后文件才对其他应用可见
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "$relativePath/${apk.name}"
    }.getOrNull()
}

/**
 * PackageInstaller 会话安装的结果回调。界面刷新靠回 ON_RESUME 后查 PackageManager，
 * 这里只记日志，并在 STATUS_PENDING_USER_ACTION 时补拉系统的确认界面（部分 ROM 不自己弹）。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        updateLogger.info { "PackageInstaller status=$status message=$message" }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

private val signatureFlags: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

/** 签名证书公钥的 SHA-256；多个签名者用逗号分隔，读不到返回 null。 */
private fun PackageInfo.signerDigest(): String? = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        signatures
    }
    signatures?.joinToString(",") { signature ->
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}.getOrNull()

/**
 * 更新包与本机签名不一致时，系统安装器只会给一句「应用未安装」。
 * 提前判断并说明原因（本地 debug 包装 CI 包就是这种情况）；读不到签名时放行，交给系统兜底。
 */
private fun signatureMatches(context: Context, apk: File): Boolean {
    val pm = context.packageManager
    val flags = signatureFlags
    val installedInfo = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }.getOrNull()
    val archiveInfo = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }
    }.getOrNull()
    val installed = installedInfo?.signerDigest()
    val archive = archiveInfo?.signerDigest()

    // 桥接版更新到 myBV 是“新装另一个应用”，允许签名换新；优先用构建时固定的证书指纹。
    if (context.packageName == LegacyMigrationContract.LEGACY_APP_ID &&
        archiveInfo?.packageName == LegacyMigrationContract.NEW_APP_ID
    ) {
        val pinned = BuildConfig.MYBV_CERT_SHA256.trim()
        if (pinned.isNotEmpty()) {
            return archive.equals(pinned, ignoreCase = true)
        }
    }

    if (installed.isNullOrEmpty() || archive.isNullOrEmpty()) return true
    return installed.equals(archive, ignoreCase = true)
}

/**
 * 装错包 / 降级保护：安装包的包名必须与预期一致（桥接版装的是 myBV，同包自更新装的是自己），
 * versionCode 不能低于已安装版本。读不到安装包信息时放行，交给系统安装器兜底。
 */
private fun apkTargetBlocker(context: Context, apk: File): String? = runCatching {
    val pm = context.packageManager
    val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apk.absolutePath, 0)
    } ?: return@runCatching null
    val expected = if (context.packageName == LegacyMigrationContract.LEGACY_APP_ID) {
        LegacyMigrationContract.NEW_APP_ID
    } else {
        context.packageName
    }
    if (archive.packageName != expected) return@runCatching "安装包与当前应用不匹配（${archive.packageName}）"
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(archive.packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(archive.packageName, 0)
    } ?: return@runCatching null // 目标应用还没装过（桥接版首次装 myBV），没有降级可言
    @Suppress("DEPRECATION")
    if (archive.versionCode < installed.versionCode) {
        "安装包版本 ${archive.versionCode} 低于已安装的 ${installed.versionCode}"
    } else {
        null
    }
}.getOrNull()

/**
 * 系统安装器返回后确认是否真的装上了：拿安装包的包名去查已安装版本，versionCode 不低于安装包即算成功。
 * 同包自更新和桥接版装 myBV 走同一套判断；读不到安装包信息按未安装处理。
 */
private fun installSucceeded(context: Context, apk: File): Boolean = runCatching {
    val pm = context.packageManager
    val flags = signatureFlags
    val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apk.absolutePath, flags)
    } ?: return@runCatching false
    // 桥接版装的是另一个应用，按安装包自己的包名查；同包自更新则查自己
    val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(archive.packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(archive.packageName, 0)
    }
    @Suppress("DEPRECATION")
    installed.versionCode >= archive.versionCode
}.getOrDefault(false)

/**
 * 是否有可装的更新。桥接版装的是另一个应用（myBV），拿自己的 versionCode 和 myBV 的
 * revision 比没有意义：只要 myBV 还没装就提示安装；新版 myBV 则比较自己的 versionCode。
 */
internal fun updateAvailable(context: Context, revision: Int): Boolean =
    if (context.packageName == LegacyMigrationContract.LEGACY_APP_ID) {
        runCatching {
            context.packageManager.getPackageInfo(LegacyMigrationContract.NEW_APP_ID, 0)
        }.isFailure
    } else {
        revision > BuildConfig.VERSION_CODE
    }
