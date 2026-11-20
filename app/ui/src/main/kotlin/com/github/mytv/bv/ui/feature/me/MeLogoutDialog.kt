package com.github.mytv.bv.ui.feature.me

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.mytv.bv.appui.R
import com.github.mytv.bv.ui.designsystem.component.BvButton
import com.github.mytv.bv.ui.designsystem.component.BvButtonStyle
import com.github.mytv.bv.ui.designsystem.theme.BvType
import com.github.mytv.bv.ui.feature.login.LoginDialogFrame

@Composable
internal fun MeLogoutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LoginDialogFrame(onDismiss = onDismiss) {
        Text(stringResource(R.string.me_logout), style = BvType.PanelTitle)
        Text(
            stringResource(R.string.me_logout_message),
            style = BvType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .align(Alignment.End)
                .focusRestorer()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BvButton(
                text = stringResource(R.string.ds_cancel),
                onClick = onDismiss,
                style = BvButtonStyle.Outlined,
            )
            BvButton(
                text = stringResource(R.string.me_logout),
                onClick = onConfirm,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        }
    }
}
