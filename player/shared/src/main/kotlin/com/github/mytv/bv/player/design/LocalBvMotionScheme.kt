package com.github.mytv.bv.player.design

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Material3 1.5 beta 移除了 `MaterialTheme.LocalMotionScheme`；
 * app 与 player 的 Modifier.Node 统一从这里读取当前主题的 MotionScheme。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val LocalBvMotionScheme = staticCompositionLocalOf { MotionScheme.expressive() }
