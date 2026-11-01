package com.github.mytv.bv.ui.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.mytv.bv.ui.designsystem.focus.bvFocus
import com.github.mytv.bv.ui.designsystem.focus.focusContainerColor
import com.github.mytv.bv.ui.designsystem.focus.focusContentColor
import com.github.mytv.bv.ui.designsystem.focus.showFocus
import com.github.mytv.bv.ui.designsystem.theme.BvType

/**
 * 原型 `.m-chip`：未选中 圆角 10 + 1dp outlineVariant 内描边；选中 全圆角 + secondaryContainer。
 * selectOnFocus = true 时聚焦即选中（电视顶栏标签）。[trailing] 放在文字后面（下拉箭头、数量角标）。
 * [onLongClick] 非空时（触屏长按，如搜索历史删除）走 combinedClickable，Surface 自身不再接点击。
 */
@Composable
fun BvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selectOnFocus: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    textStyle: TextStyle = BvType.Small.copy(fontWeight = FontWeight.Medium),
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        if (selected) 100.dp else 10.dp,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "chipCorner"
    )
    val baseContainer by animateColorAsState(
        if (selected) scheme.secondaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "chipContainer"
    )
    val baseContent = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val container by focusContainerColor(focused, baseContainer)
    val content by focusContentColor(focused, baseContent)
    val shape = RoundedCornerShape(corner)
    val chipModifier = modifier.bvFocus(onFocused = { if (it && selectOnFocus && !selected) onClick() })
    val body: @Composable () -> Unit = {
        Row(
            Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = textStyle, maxLines = 1)
            trailing?.invoke()
        }
    }
    if (onLongClick != null) {
        // 长按版：点击交给 combinedClickable，Surface 只用不可点的那个重载画外形
        Surface(
            modifier = chipModifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
            shape = shape,
            color = container,
            contentColor = content,
            border = if (selected || showFocus(focused)) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) { body() }
    } else {
        Surface(
            onClick = onClick,
            modifier = chipModifier,
            enabled = enabled,
            shape = shape,
            color = container,
            contentColor = content,
            border = if (selected || showFocus(focused)) null else BorderStroke(1.dp, scheme.outlineVariant),
            interactionSource = interaction,
        ) { body() }
    }
}
