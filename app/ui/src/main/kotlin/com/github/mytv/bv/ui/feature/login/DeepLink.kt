package com.github.mytv.bv.ui.feature.login

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.github.mytv.bv.entity.BvScheme
import com.github.mytv.bv.ui.navigation.ToViewRoute
import com.github.mytv.bv.ui.navigation.TopLevel
import com.github.mytv.bv.ui.navigation.VideoRoute
import com.github.mytv.bv.ui.navigation.navigateTopLevel

/**
 * 处理外部深链：bugvideo://qrtoken 导入账号，bugvideo://video/{aid} 打开视频，
 * bugvideo://recommend 回首页，bugvideo://toview 打开稍后再看。
 * 每个 Intent 只处理一次（rememberSaveable 记住已处理过）。
 */
@Composable
fun DeepLinkEffect(intent: Intent?, nav: NavController) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(intent) {
        val uri = intent?.data ?: return@LaunchedEffect
        val text = uri.toString()
        if (handled == text) return@LaunchedEffect

        when (uri.host) {
            BvScheme.Video.HOST -> {
                handled = text
                uri.pathSegments.firstOrNull()?.toLongOrNull()
                    ?.let { nav.navigate(VideoRoute(it)) }
            }

            BvScheme.Recommend.host -> {
                handled = text
                nav.navigateTopLevel(TopLevel.Home)
            }

            BvScheme.ToView.host -> {
                handled = text
                nav.navigate(ToViewRoute) { launchSingleTop = true }
            }

            BvScheme.QrToken.HOST -> {
                handled = text
                pending = text
            }
        }
    }
    pending?.let { text ->
        QrTokenImportDialog(uri = Uri.parse(text), onDismiss = { pending = null })
    }
}
