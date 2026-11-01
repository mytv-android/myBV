package com.github.mytv.bv.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.github.mytv.bv.ui.designsystem.adaptive.isTelevision

/**
 * 电视上整体关闭桌面小组件。
 *
 * leanback 桌面本来就没有小组件位；更重要的是小组件这条链路会拉起 WorkManager，
 * 而 WorkManager 首次初始化要同步打开 / 迁移自己的数据库，在慢盒子上会把启动画面卡住。
 * 关掉之后电视上不会有任何代码调用 WorkManager.getInstance()，它也就不会被初始化。
 */
internal fun widgetsSupported(context: Context): Boolean =
    runCatching { !context.isTelevision() }.getOrDefault(false)

/** 电视上直接忽略桌面的更新 / 点击，连 Glance 的渲染和 WorkManager 依赖都不会碰到。 */
abstract class BvGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (!widgetsSupported(context)) return
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
