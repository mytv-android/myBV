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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.mytv.bv.BuildConfig
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.migration.LegacyMigrationContract
import com.github.mytv.bv.network.GithubApi
import com.github.mytv.bv.network.entity.Release
import com.github.mytv.bv.ui.PreferenceWrites
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.feature.migration.shouldShowLegacyMigrationPrompt
import com.github.mytv.bv.util.CacheManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.content.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

private val updateLogger = KotlinLogging.logger("BvUpdateDialog")

private enum class UpdateStatus {
    UpdatingInfo, Ready, Downloading, Installing,
    NoAvailableUpdate, CheckError, DownloadError, InstallError
}

/** 授权页跳转次数上限：授权页打得开但固件不认这个开关时，防止「回前台 → 重试 → 再跳授权页」死循环。 */
private const val MAX_SETTINGS_ATTEMPTS = 2

/** 手动安装时导出 APK 的目录名（Android 10+ 是公共下载目录下的子目录，更老的系统退到外部应用目录）。 */
private const val MANUAL_INSTALL_DIR = "update"

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
 * 同包自更新和桥接版装 myBV 走同一套判断；读不到安装包信息按未安装处理，让对话框回到可重试的状态。
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
private fun updateAvailable(context: Context, revision: Int): Boolean =
    if (context.packageName == LegacyMigrationContract.LEGACY_APP_ID) {
        runCatching {
            context.packageManager.getPackageInfo(LegacyMigrationContract.NEW_APP_ID, 0)
        }.isFailure
    } else {
        revision > BuildConfig.VERSION_CODE
    }

/**
 * 检查更新对话框：逻辑照抄 app/shared 的旧 UpdateDialog（GithubApi 取最新构建 → 比较 revision →
 * 下载到 cache/update_downloader → FileProvider 安装），只换成设计系统外观。
 * 网络与文件操作在 IO 线程，状态一律回到主线程再写。
 */
@Composable
internal fun SettingsUpdateDialog(
    onDismiss: () -> Unit,
    initialRelease: Release? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(UpdateStatus.UpdatingInfo) }
    var release by remember { mutableStateOf<Release?>(null) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var targetProgress by remember { mutableFloatStateOf(0f) }
    val progress by animateFloatAsState(targetProgress, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "updateProgress")
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    // 交给系统安装器之后要等本页重新 resume 才能确认结果，否则「安装中」会一直挂着
    var installingApk by remember { mutableStateOf<File?>(null) }
    var awaitingInstall by remember { mutableStateOf(false) }
    // 三级安装兜底全灭、跳了授权页：等用户从设置页回来，回前台时无条件重试一次安装
    var awaitingPermission by remember { mutableStateOf(false) }
    // 已经跳了几次授权页；到上限就直接导出手动安装，不在弹窗和设置页之间死循环
    var settingsAttempts by remember { mutableIntStateOf(0) }

    /**
     * 三级安装兜底全灭后的退路：有额度就跳「安装未知应用」授权页（用户回来再试），
     * 跳不了或已到上限就把 APK 导到外部目录提示手动安装——不留「无法打开设置」的死路。
     */
    val onInstallChainFailed: suspend (File) -> Unit = { apk ->
        if (settingsAttempts < MAX_SETTINGS_ATTEMPTS && openUnknownSourcesSettings(context)) {
            settingsAttempts++
            awaitingPermission = true
            status = UpdateStatus.Installing
        } else {
            awaitingPermission = false
            val exported = withContext(Dispatchers.IO) { exportApkForManualInstall(context, apk) }
            errorDetail = if (exported != null) {
                context.getString(R.string.settings_update_install_manual, exported)
            } else {
                context.getString(R.string.settings_update_install_export_failed)
            }
            status = UpdateStatus.InstallError
        }
    }

    val checkUpdate: () -> Unit = {
        status = UpdateStatus.UpdatingInfo
        errorDetail = null
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
                    release = latest
                    updateLogger.info { "Find latest version ${latest.name}" }
                    status = if (hasUpdate) UpdateStatus.Ready else UpdateStatus.NoAvailableUpdate
                }
                .onFailure {
                    updateLogger.warn(it) { "Failed to get latest version" }
                    errorDetail = it.message ?: it::class.simpleName
                    status = UpdateStatus.CheckError
                }
        }
    }

    val startUpdate: () -> Unit = {
        val latest = release
        if (latest != null) {
            status = UpdateStatus.Downloading
            errorDetail = null
            downloaded = 0L
            total = 0L
            targetProgress = 0f
            // 重新下载就是全新的一次安装尝试，授权页额度重新计算
            settingsAttempts = 0
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.cacheDir, CacheManager.UPDATE_CACHE_DIR)
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, "${UUID.randomUUID()}.apk")
                        file.createNewFile()
                        GithubApi.downloadUpdate(
                            latest,
                            file,
                            object : ProgressListener {
                                private var lastUiUpdate = 0L

                                override suspend fun onProgress(bytesSentTotal: Long, contentLength: Long?) {
                                    // 网络分块可能非常频繁：UI 最多 10Hz 更新一次
                                    val now = SystemClock.elapsedRealtime()
                                    val finished = contentLength != null && bytesSentTotal >= contentLength
                                    if (!finished && now - lastUiUpdate < 100L) return
                                    lastUiUpdate = now
                                    withContext(Dispatchers.Main) {
                                        downloaded = bytesSentTotal
                                        total = contentLength ?: 0L
                                        targetProgress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                    }
                                }
                            },
                        )
                        file
                    }
                }
                result
                    .onSuccess { file ->
                        if (active) {
                            // 缓存写入后检查阈值；preserve 保护刚下载的安装包不被清理
                            withContext(Dispatchers.IO) {
                                runCatching { CacheManager(context).checkCache(preserve = file) }
                            }
                            val blocker = withContext(Dispatchers.IO) {
                                apkTargetBlocker(context, file)
                                    ?: if (signatureMatches(context, file)) {
                                        null
                                    } else {
                                        "更新包签名与当前应用不一致，无法覆盖安装"
                                    }
                            }
                            if (blocker != null) {
                                errorDetail = blocker
                                status = UpdateStatus.InstallError
                            } else {
                                status = UpdateStatus.Installing
                                installingApk = file
                                if (installApk(context, file)) {
                                    awaitingInstall = true
                                } else {
                                    onInstallChainFailed(file)
                                }
                            }
                        }
                    }
                    .onFailure {
                        updateLogger.warn(it) { "Failed to download update" }
                        errorDetail = it.message ?: it::class.simpleName
                        status = UpdateStatus.DownloadError
                    }
            }
        }
    }

    LaunchedEffect(Unit) {
        val initial = initialRelease
        if (initial != null) {
            release = initial
            status = UpdateStatus.Ready
        } else {
            checkUpdate()
        }
    }

    // 系统安装器是另一个应用：装上、取消之后本页只会 resume，不会重建。
    // 回到前台时确认结果——装好了就关掉对话框（桥接版随后交给迁移提示）；
    // 从授权页回来则无条件重试一次安装，仍装不上才按次数上限转手动安装。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, awaitingInstall, awaitingPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val apk = installingApk
                val fromSettings = awaitingPermission
                if (apk != null && (awaitingInstall || fromSettings)) {
                    awaitingInstall = false
                    awaitingPermission = false
                    scope.launch {
                        var installed = withContext(Dispatchers.IO) { installSucceeded(context, apk) }
                        if (!installed) {
                            // 安装器可能比 PackageManager 提交稍早返回，稍等一下再判一次
                            delay(1500)
                            installed = withContext(Dispatchers.IO) { installSucceeded(context, apk) }
                        }
                        if (installed) {
                            active = false
                            onDismiss()
                            return@launch
                        }
                        // 授权页回来不重新判断权限（canRequestPackageInstalls 在盒子上恒 false，
                        // 问它等于白问），直接再走一遍安装链：用户可能刚开了开关，
                        // 也可能固件根本不认这个开关——后者由次数上限转手动安装兜底
                        if (fromSettings) {
                            if (installApk(context, apk)) {
                                awaitingInstall = true
                            } else {
                                onInstallChainFailed(apk)
                            }
                            return@launch
                        }
                        if (status == UpdateStatus.Installing) {
                            errorDetail = context.getString(R.string.settings_update_install_cancelled)
                            status = UpdateStatus.InstallError
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val busy = status == UpdateStatus.Downloading || status == UpdateStatus.Installing
    val close: () -> Unit = {
        active = false
        onDismiss()
    }
    // 按钮在下载/安装中保持禁用避免误触，但返回键必须能退出：安装完从安装器返回时
    // 对话框还停在「安装中」，不让返回就只能按主页键杀出来。
    val dismiss: () -> Unit = {
        if (!busy) {
            close()
        }
    }
    val dismissRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }
    val hasConfirm = status == UpdateStatus.Ready || status == UpdateStatus.CheckError ||
        status == UpdateStatus.DownloadError || status == UpdateStatus.InstallError
    LaunchedEffect(status) {
        withFrameNanos { }
        if (hasConfirm) confirmRequester.tryFocusEnter() else dismissRequester.tryFocusEnter()
    }

    SettingsDialog(onDismiss = close) {
        Text(
            when (status) {
                UpdateStatus.UpdatingInfo -> stringResource(R.string.settings_update_title_checking)
                UpdateStatus.Ready -> release?.name.orEmpty()
                UpdateStatus.Downloading -> stringResource(R.string.settings_update_title_downloading)
                UpdateStatus.Installing -> stringResource(R.string.settings_update_title_installing)
                UpdateStatus.NoAvailableUpdate -> stringResource(R.string.settings_update_title_none)
                UpdateStatus.CheckError -> stringResource(R.string.settings_update_title_check_error)
                UpdateStatus.DownloadError -> stringResource(R.string.settings_update_title_download_error)
                UpdateStatus.InstallError -> stringResource(R.string.settings_update_title_install_error)
            },
            style = BvType.PanelTitle,
        )
        when (status) {
            UpdateStatus.UpdatingInfo -> LoadingIndicator()
            UpdateStatus.Ready -> Text(
                release?.body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_update_body_empty),
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                style = BvType.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UpdateStatus.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_update_progress, downloaded, total),
                    modifier = Modifier.fillMaxWidth(),
                    style = BvType.Label.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val bodyText = when (status) {
                    UpdateStatus.Installing -> if (awaitingPermission) {
                        // 已跳去授权页：告诉用户回来会自动重试，这是第几次
                        stringResource(
                            R.string.settings_update_body_grant_permission,
                            settingsAttempts,
                            MAX_SETTINGS_ATTEMPTS,
                        )
                    } else {
                        stringResource(R.string.settings_update_body_installing)
                    }

                    UpdateStatus.DownloadError -> stringResource(R.string.settings_update_body_download_error)
                    UpdateStatus.InstallError -> stringResource(R.string.settings_update_body_install_error)
                    UpdateStatus.CheckError -> stringResource(R.string.settings_update_body_check_error)
                    else -> stringResource(R.string.settings_update_body_none)
                }
                Text(
                    bodyText,
                    style = BvType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorDetail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = BvType.Small, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SettingsDialogActions(
            confirm = when (status) {
                UpdateStatus.Ready -> stringResource(R.string.settings_update_now)
                UpdateStatus.CheckError, UpdateStatus.DownloadError, UpdateStatus.InstallError ->
                    stringResource(R.string.settings_update_retry)

                else -> null
            },
            onConfirm = {
                if (status == UpdateStatus.Ready) startUpdate() else checkUpdate()
            },
            dismiss = stringResource(
                when (status) {
                    UpdateStatus.UpdatingInfo -> R.string.settings_update_dismiss_checking
                    UpdateStatus.Ready -> R.string.settings_update_dismiss_ready
                    UpdateStatus.NoAvailableUpdate -> R.string.settings_update_dismiss_none
                    UpdateStatus.Downloading, UpdateStatus.Installing -> R.string.settings_update_dismiss_busy
                    else -> R.string.settings_update_dismiss_error
                }
            ),
            onDismiss = dismiss,
            dismissEnabled = !busy,
            confirmRequester = confirmRequester,
            dismissRequester = dismissRequester,
        )
    }
}

/**
 * 进入应用时静默检查一次更新，只有存在更高版本才弹窗；迁移提示出现时让路。
 * 桥接版也从这里获得“进入应用即检查更新”的行为。
 */
@Composable
internal fun LaunchUpdatePrompt() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Release?>(null) }
    LaunchedEffect(Unit) {
        val skip = withContext(Dispatchers.IO) {
            runCatching { shouldShowLegacyMigrationPrompt(context) }.getOrDefault(false)
        }
        if (skip) return@LaunchedEffect
        val latest = withContext(Dispatchers.IO) {
            runCatching {
                PreferenceWrites.awaitPending()
                GithubApi.getLatestBuild()
            }.getOrNull()
        } ?: return@LaunchedEffect
        val revision = latest.revision ?: return@LaunchedEffect
        if (updateAvailable(context, revision)) pending = latest
    }
    pending?.let { latest ->
        SettingsUpdateDialog(
            onDismiss = { pending = null },
            initialRelease = latest,
        )
    }
}
