package com.github.mytv.bv.player.entity

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.github.mytv.bv.player.shared.R

enum class VideoPlayerMenuNavItem(private val strRes: Int, val icon: @Composable () -> Painter) {
    Picture(R.string.video_player_menu_nav_picture, { painterResource(R.drawable.ic_image_outlined_24) }),
    Danmaku(R.string.video_player_menu_nav_danmaku, { painterResource(R.drawable.ic_clear_all_outlined_24) }),
    ClosedCaption(R.string.video_player_menu_nav_subtitle, { painterResource(R.drawable.ic_closed_caption_outlined_24) }),
    Others(R.string.video_player_menu_nav_others, { painterResource(R.drawable.ic_more_vert_outlined_24) });

    fun getDisplayName(context: Context) = context.getString(strRes)
}
