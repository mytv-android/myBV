package com.github.mytv.bv.ui.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.http.BiliHttpProxyApi
import dev.aaa1115910.biliapi.http.util.BiliAppConf
import dev.aaa1115910.biliapi.repositories.ChannelRepository
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.player.PlayerHttpClient
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.network.GithubMirror
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.ui.PreferenceWrites
import org.koin.compose.koinInject

private enum class ProxyField { Http, Grpc }

/**
 * 「网络与代理」：代理开关 + HTTP / gRPC 代理服务器、官方 CDN、接口类型（Web / App）、网络测试入口。
 * 逻辑照抄旧电视 NetworkSetting 与 ApiSetting。
 */
@Composable
internal fun NetworkSettings(onSpeedTest: () -> Unit, container: SettingsContainer) {
    val channelRepository: ChannelRepository = koinInject()
    val initial = rememberPreferenceSnapshot {
        NetworkPreferences(
            enableProxy = Prefs.enableProxy,
            proxyHttpServer = Prefs.proxyHttpServer,
            proxyGrpcServer = Prefs.proxyGRPCServer,
            preferOfficialCdn = Prefs.preferOfficialCdn,
            apiType = Prefs.apiType,
            ipv4Only = Prefs.ipv4Only,
            githubMirror = Prefs.githubMirror,
        )
    } ?: return
    var enableProxy by remember { mutableStateOf(initial.enableProxy) }
    var ipv4Only by remember { mutableStateOf(initial.ipv4Only) }
    var proxyHttpServer by remember { mutableStateOf(initial.proxyHttpServer) }
    var proxyGrpcServer by remember { mutableStateOf(initial.proxyGrpcServer) }
    var preferOfficialCdn by remember { mutableStateOf(initial.preferOfficialCdn) }
    var apiType by remember { mutableStateOf(initial.apiType) }
    var githubMirror by remember { mutableStateOf(initial.githubMirror) }
    var pickingMirror by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProxyField?>(null) }
    val emptyText = stringResource(R.string.settings_net_proxy_empty)

    container {
        Column(Modifier.fillMaxWidth()) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_net_proxy),
                subtitle = stringResource(R.string.settings_net_proxy_desc),
                checked = enableProxy,
                onCheckedChange = { enable ->
                    enableProxy = enable
                    PreferenceWrites.enqueue {
                        Prefs.enableProxy = enable
                        if (enable) BVApp.instance?.initProxy()
                    }
                },
            )
            AnimatedVisibility(visible = enableProxy) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SettingNavRow(
                        title = stringResource(R.string.settings_net_proxy_http),
                        subtitle = proxyHttpServer.ifBlank { emptyText },
                        onClick = { editing = ProxyField.Http },
                    )
                    SettingNavRow(
                        title = stringResource(R.string.settings_net_proxy_grpc),
                        subtitle = proxyGrpcServer.ifBlank { emptyText },
                        onClick = { editing = ProxyField.Grpc },
                    )
                }
            }
        }

        SettingSwitchRow(
            title = stringResource(R.string.settings_net_cdn),
            subtitle = stringResource(R.string.settings_net_cdn_desc),
            checked = preferOfficialCdn,
            onCheckedChange = {
                preferOfficialCdn = it
                PreferenceWrites.enqueue { Prefs.preferOfficialCdn = it }
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_net_ipv4_only),
            subtitle = stringResource(R.string.settings_net_ipv4_only_desc),
            checked = ipv4Only,
            onCheckedChange = { enable ->
                ipv4Only = enable
                // HTTP / 图片 client 的 DNS 实时读取开关，立即生效；
                // gRPC 通道没有过滤入口，重建一次默认通道
                BiliAppConf.ipv4Only = enable
                PlayerHttpClient.ipv4Only = enable
                PreferenceWrites.enqueue {
                    Prefs.ipv4Only = enable
                    runCatching {
                        channelRepository.initDefaultChannel(
                            Prefs.accessToken,
                            Prefs.buvid,
                            Prefs.uid.takeIf { it != 0L }
                        )
                    }
                }
            },
        )

        SettingChoiceGroup(
            label = stringResource(R.string.settings_net_api),
            options = ApiType.entries.toList(),
            isSelected = { it == apiType },
            optionLabel = {
                when (it) {
                    ApiType.Web -> stringResource(R.string.settings_net_api_web)
                    ApiType.App -> stringResource(R.string.settings_net_api_app)
                }
            },
            onSelect = {
                apiType = it
                PreferenceWrites.enqueue { Prefs.apiType = it }
            },
            // HDR Vivid（129）只有 App 端的 gRPC 接口会下发，网页端接口的 fnval
            // 一超过 4048 就 -400，结构上拿不到；这条说明是用户能找到的唯一线索
            description = stringResource(R.string.settings_net_api_desc),
        )

        SettingRows(stringResource(R.string.settings_net_tools)) {
            SettingNavRow(
                title = stringResource(R.string.settings_net_github_mirror),
                subtitle = when (githubMirror) {
                    GithubMirror.Auto -> stringResource(R.string.settings_net_github_mirror_auto)
                    GithubMirror.Direct -> stringResource(R.string.settings_net_github_mirror_direct)
                    else -> stringResource(R.string.settings_net_github_mirror_pinned, githubMirror.label)
                },
                value = githubMirror.label,
                onClick = { pickingMirror = true },
            )
            SettingNavRow(
                title = stringResource(R.string.settings_net_speed_test),
                subtitle = stringResource(R.string.settings_net_speed_test_desc),
                onClick = onSpeedTest,
            )
        }

        if (pickingMirror) {
            GithubMirrorDialog(
                title = stringResource(R.string.settings_net_github_mirror_pick),
                selected = githubMirror,
                onDismiss = { pickingMirror = false },
                onPick = { mirror ->
                    pickingMirror = false
                    githubMirror = mirror
                    PreferenceWrites.enqueue { Prefs.githubMirror = mirror }
                },
            )
        }

        when (editing) {
            ProxyField.Http -> ProxyServerDialog(
                title = stringResource(R.string.settings_net_proxy_http),
                server = proxyHttpServer,
                onDismiss = { editing = null },
                onConfirm = { server ->
                    editing = null
                    proxyHttpServer = server
                    PreferenceWrites.enqueue {
                        Prefs.proxyHttpServer = server
                        runCatching { BiliHttpProxyApi.createClient(server) }
                    }
                },
            )

            ProxyField.Grpc -> ProxyServerDialog(
                title = stringResource(R.string.settings_net_proxy_grpc),
                server = proxyGrpcServer,
                onDismiss = { editing = null },
                onConfirm = { server ->
                    editing = null
                    proxyGrpcServer = server
                    PreferenceWrites.enqueue {
                        Prefs.proxyGRPCServer = server
                        runCatching {
                            channelRepository.initProxyChannel(
                                accessKey = Prefs.accessToken,
                                buvid = Prefs.buvid,
                                proxyServer = server,
                                mid = Prefs.uid.takeIf { it != 0L },
                            )
                        }
                    }
                },
            )

            null -> Unit
        }
    }
}

/** 编辑代理服务器（旧 ProxyServerEditDialog）：去掉换行与 http(s):// 前缀后保存。 */
@Composable
private fun ProxyServerDialog(
    title: String,
    server: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(server) }
    val fieldRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fieldRequester.requestFocus() }
    }
    SettingsDialog(onDismiss) {
        Text(title, style = BvType.PanelTitle)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldRequester),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text(stringResource(R.string.settings_net_proxy_hint)) },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painterResource(R.drawable.ic_info_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_net_proxy_warning),
                style = BvType.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsDialogActions(
            confirm = stringResource(R.string.settings_confirm),
            onConfirm = {
                onConfirm(
                    input
                        .replace("\n", "")
                        .replace("https://", "")
                        .replace("http://", "")
                )
            },
            dismiss = stringResource(R.string.settings_cancel),
            onDismiss = onDismiss,
        )
    }
}

private data class NetworkPreferences(
    val enableProxy: Boolean,
    val proxyHttpServer: String,
    val proxyGrpcServer: String,
    val preferOfficialCdn: Boolean,
    val apiType: ApiType,
    val ipv4Only: Boolean,
    val githubMirror: GithubMirror,
)

/**
 * 更新线路选择：每条线路后跟一句限制说明，避免用户以为「不代理 API」的线路
 * 也能用于检查更新——那几条只加速安装包下载。
 */
@Composable
private fun GithubMirrorDialog(
    title: String,
    selected: GithubMirror,
    onDismiss: () -> Unit,
    onPick: (GithubMirror) -> Unit,
) {
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    SettingsDialog(onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SettingRows("") {
            GithubMirror.entries.forEachIndexed { index, mirror ->
                val summary = when (mirror) {
                    GithubMirror.Auto -> stringResource(R.string.settings_net_github_mirror_auto)
                    GithubMirror.Direct -> stringResource(R.string.settings_net_github_mirror_direct)
                    GithubMirror.ProxyOrg, GithubMirror.ProxyCom -> "更新检查与下载都可用"
                    else -> "只加速安装包下载，检查更新仍走其它线路"
                }
                SettingNavRow(
                    title = if (mirror == selected) "${mirror.label} · 当前" else mirror.label,
                    subtitle = summary,
                    showChevron = false,
                    modifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
                    onClick = { onPick(mirror) },
                )
            }
        }
        SettingsDialogActions(
            confirm = stringResource(R.string.settings_net_github_mirror_close),
            onConfirm = onDismiss,
            dismiss = null,
            onDismiss = onDismiss,
        )
    }
}
