package com.github.mytv.bv.ui.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.github.mytv.bv.BVApp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.util.Prefs
import com.github.mytv.bv.ui.PreferenceWrites
import com.github.mytv.bv.viewmodel.UserSwitchViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 「账号与安全」：多账号管理（AccountsRoute）、用户锁（UserLockRoute）、无痕模式（Prefs.incognitoMode）。
 */
private data class AccountPrefs(
    val incognito: Boolean,
    val disableRcmd: Boolean,
    val teenagersMode: Boolean,
)

@Composable
internal fun AccountSettings(onAccounts: () -> Unit, onUserLock: () -> Unit, container: SettingsContainer) {
    val userSwitch: UserSwitchViewModel = koinViewModel()
    val initial = rememberPreferenceSnapshot {
        AccountPrefs(
            incognito = Prefs.incognitoMode,
            disableRcmd = Prefs.disableRcmd,
            teenagersMode = Prefs.teenagersMode,
        )
    } ?: return
    var incognito by remember { mutableStateOf(initial.incognito) }
    var disableRcmd by remember { mutableStateOf(initial.disableRcmd) }
    var teenagersMode by remember { mutableStateOf(initial.teenagersMode) }
    LaunchedEffect(Unit) { userSwitch.updateData() }

    val locked = userSwitch.currentUser.lock.isNotBlank()
    container {
        SettingRows(stringResource(R.string.settings_account_group)) {
            SettingNavRow(
                title = stringResource(R.string.settings_account_manage),
                subtitle = stringResource(R.string.settings_account_count, userSwitch.userDbList.size),
                onClick = onAccounts,
            )
            SettingNavRow(
                title = stringResource(R.string.settings_account_lock),
                subtitle = stringResource(
                    if (locked) R.string.settings_account_lock_on else R.string.settings_account_lock_off
                ),
                onClick = onUserLock,
            )
        }

        SettingSwitchRow(
            title = stringResource(R.string.settings_account_incognito),
            subtitle = stringResource(R.string.settings_account_incognito_desc),
            checked = incognito,
            onCheckedChange = {
                incognito = it
                PreferenceWrites.enqueue { Prefs.incognitoMode = it }
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_account_disable_rcmd),
            subtitle = stringResource(R.string.settings_account_disable_rcmd_desc),
            checked = disableRcmd,
            onCheckedChange = {
                disableRcmd = it
                PreferenceWrites.enqueue {
                    Prefs.disableRcmd = it
                    BVApp.instance?.refreshCustomParams()
                }
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_account_teenagers),
            subtitle = stringResource(R.string.settings_account_teenagers_desc),
            checked = teenagersMode,
            onCheckedChange = {
                teenagersMode = it
                PreferenceWrites.enqueue {
                    Prefs.teenagersMode = it
                    BVApp.instance?.refreshCustomParams()
                }
            },
        )
    }
}
