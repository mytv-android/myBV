package com.github.mytv.bv.ui.feature.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.adaptive.LocalDeviceProfile
import com.github.mytv.bv.ui.designsystem.adaptive.NavKind
import com.github.mytv.bv.ui.designsystem.theme.BvDimens
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.feature.migration.shouldShowLegacyMigrationPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 更新提示卡的常驻宿主：在整个进程里只挂一次（MainActivity）。
 *
 * 卡片本身仍然是 `Dialog`（独立窗口），所以它只在状态真的需要用户回答时才出现；
 * 下载进行中（[UpdateStatus.Downloading]）它退出组合树，由 [UpdatePillHost] 的 pill 接管——
 * 这正是「下载时可以继续浏览」的实现方式：没有窗口挡在前面。
 *
 * 同时承担系统安装器返回后的结果确认：安装器是另一个应用，回来只有 ON_RESUME，
 * 这个宿主常驻，所以安装中途提示卡被收起也不会漏掉结果。
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) UpdateManager.onResume(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!state.cardUp) return

    SettingsUpdateDialog(
        state = state,
        onConfirm = {
            if (state.status == UpdateStatus.Ready) {
                UpdateManager.download(context)
            } else {
                UpdateManager.check(context)
            }
        },
        onDismiss = UpdateManager::dismissPrompt,
    )
}

/** 检查更新提示卡：准备就绪显示更新说明，「立即更新」之后收起，交给 pill 显示进度。 */
@Composable
private fun SettingsUpdateDialog(
    state: UpdateUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = state.busy
    val ready = state.status == UpdateStatus.Ready
    // 安装中按钮禁用避免误触，但返回键必须能退出：安装完从安装器返回时卡片还停在「安装中」，
    // 不让返回就只能按主页键杀出来。结果确认由常驻的 UpdatePromptHost 负责，不受这里影响。
    val dismiss: () -> Unit = onDismiss
    val dismissRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }
    val hasConfirm = ready || state.status == UpdateStatus.CheckError ||
        state.status == UpdateStatus.DownloadError || state.status == UpdateStatus.InstallError
    LaunchedEffect(state.status) {
        withFrameNanos { }
        if (hasConfirm) confirmRequester.tryFocusEnter() else dismissRequester.tryFocusEnter()
    }

    SettingsDialog(onDismiss = dismiss) {
        Text(
            when (state.status) {
                UpdateStatus.Checking -> stringResource(R.string.settings_update_title_checking)
                UpdateStatus.Ready -> state.release?.name.orEmpty()
                UpdateStatus.Downloading -> stringResource(R.string.settings_update_title_downloading)
                UpdateStatus.Installing -> stringResource(R.string.settings_update_title_installing)
                UpdateStatus.NoAvailableUpdate -> stringResource(R.string.settings_update_title_none)
                UpdateStatus.CheckError -> stringResource(R.string.settings_update_title_check_error)
                UpdateStatus.DownloadError -> stringResource(R.string.settings_update_title_download_error)
                UpdateStatus.InstallError -> stringResource(R.string.settings_update_title_install_error)
                UpdateStatus.Idle -> stringResource(R.string.settings_update_title_checking)
            },
            style = BvType.PanelTitle,
        )
        when (state.status) {
            UpdateStatus.Checking, UpdateStatus.Idle -> LoadingIndicator()

            UpdateStatus.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.release?.body?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_update_body_empty),
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    style = BvType.Small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_update_downloading_hint),
                    style = BvType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val bodyText = when (state.status) {
                    UpdateStatus.Installing -> if (state.awaitingPermission) {
                        // 已跳去授权页：告诉用户回来会自动重试，这是第几次
                        stringResource(
                            R.string.settings_update_body_grant_permission,
                            state.settingsAttempts,
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
                state.errorDetail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = BvType.Small, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SettingsDialogActions(
            confirm = when (state.status) {
                UpdateStatus.Ready -> stringResource(R.string.settings_update_now)
                UpdateStatus.CheckError, UpdateStatus.DownloadError, UpdateStatus.InstallError ->
                    stringResource(R.string.settings_update_retry)

                else -> null
            },
            onConfirm = onConfirm,
            dismiss = stringResource(
                when (state.status) {
                    UpdateStatus.Checking -> R.string.settings_update_dismiss_checking
                    UpdateStatus.Ready -> R.string.settings_update_dismiss_ready
                    UpdateStatus.NoAvailableUpdate -> R.string.settings_update_dismiss_none
                    UpdateStatus.Installing -> R.string.settings_update_dismiss_busy
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
 * 进入应用时静默检查一次更新，只有存在更高版本才把状态推成 Ready，由 [UpdatePromptHost] 展示。
 * 迁移提示出现时让路；进程里已经有别的更新状态时也不再检查，免得把用户正在做的事顶掉。
 */
@Composable
fun LaunchUpdatePrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (UpdateManager.state.value.status != UpdateStatus.Idle) return@LaunchedEffect
        // 迁移提示出现时让路，两个对话框不叠在一起
        val skip = withContext(Dispatchers.IO) {
            runCatching { shouldShowLegacyMigrationPrompt(context) }.getOrDefault(false)
        }
        if (skip) return@LaunchedEffect
        UpdateManager.check(context, silent = true)
    }
}

/**
 * 更新下载中的非模态 pill：不可聚焦、不拦截按键，用户照常浏览。
 *
 * 手机是全宽条、贴在底部导航栏上方；电视和平板是右下角的圆角条。进度未知时显示不确定进度条，
 * 「0%」挂在首字节到达之前读起来像卡死，所以百分比只在有进度后才出现。
 */
@Composable
private fun UpdateDownloadPill(modifier: Modifier = Modifier) {
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    val profile = LocalDeviceProfile.current
    val fullWidth = profile.navKind == NavKind.Bar
    val progress = state.downloadProgress
    val animated by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "updatePillProgress",
    )
    val badgeRotation = rememberInfiniteTransition(label = "updatePillBadge").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
        ),
        label = "updatePillBadgeSpin",
    )
    val trackModifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(120.dp)

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(badgeRotation.value),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = if (fullWidth) Modifier.weight(1f) else Modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.settings_update_pill_title, state.release?.name.orEmpty()),
                style = BvType.Label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (progress == null) {
                LinearWavyProgressIndicator(modifier = trackModifier)
            } else {
                LinearWavyProgressIndicator(
                    progress = { animated },
                    modifier = trackModifier,
                )
            }
        }
        Text(
            text = progress?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: "…",
            style = BvType.Label.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * pill 的定位：手机贴在底部导航栏上方，电视 / 平板落在右下角安全区内。
 * 只在下载中显示；宿主挂在 AppShell 的顶层 Box 里，所以任何页面都能看到。
 *
 * @param bottomBarPadding 手机底部导航栏的高度（含系统导航栏 inset），0 表示没有导航栏
 */
@Composable
fun BoxScope.UpdatePillHost(bottomBarPadding: Dp = 0.dp) {
    val state by UpdateManager.state.collectAsStateWithLifecycle()
    if (!state.showPill) return

    val profile = LocalDeviceProfile.current
    val phone = profile.navKind == NavKind.Bar
    Box(
        modifier = if (phone) {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = BvDimens.PhoneGutter,
                    end = BvDimens.PhoneGutter,
                    bottom = if (bottomBarPadding > 0.dp) bottomBarPadding + 12.dp else 24.dp,
                )
        } else {
            Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = if (profile.isTv) BvDimens.TvSafeHorizontal else BvDimens.TabletContentEnd,
                    bottom = if (profile.isTv) BvDimens.TvSafeVertical else 24.dp,
                )
        },
    ) {
        UpdateDownloadPill()
    }
}
