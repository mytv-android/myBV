package dev.aaa1115910.m3qrcode

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 二维码码点颜色（设计稿 `.m-qr i` 的 #1b1b1f）。白底卡片上的二维码文字、图标也用同一个色。 */
val QrInk: Color = Color(0xFF1B1B1F)

/**
 * 白底卡片上的二维码配色。
 *
 * [MaterialShapeQr] 不传 colorScheme 时会退回 `dynamicLightColorScheme(context)`：码点取自桌面壁纸
 * 派生的 primary/secondary（HCT tone 40 的中间调），底子是 tone 90 的浅灰，对比度只有 4:1 上下，
 * 手机摄像头做局部二值化时码点会糊成一团——这是扫码难认的主要原因，而且每台设备的壁纸不同，
 * 颜色不可预期。
 *
 * 这里固定成设计稿的深色码点 + 纯白底，`.bg` 装饰层也一并压白。做法参照 mytv `PushScreen` 的
 * qrColorScheme：二维码放在白底卡片上时前景必须压成深色那一档，否则深色主题下的浅色码点落在
 * 白底上等于反相码，不少扫码器直接认不出。
 */
val QrOnWhiteColorScheme: ColorScheme = lightColorScheme(
    primary = QrInk,
    secondary = QrInk,
    tertiary = QrInk,
    surfaceContainerHighest = Color.White,
    onSurface = Color.White,
    tertiaryContainer = Color.White,
)
