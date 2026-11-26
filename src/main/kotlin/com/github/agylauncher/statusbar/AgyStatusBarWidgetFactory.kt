package com.github.agylauncher.statusbar

import com.github.agylauncher.AgyBundle
import com.github.agylauncher.settings.AgySettingsState
import com.github.agylauncher.terminal.AgyMetricsService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * 底部状态栏监控控件工厂。
 * 在状态栏右侧注册并管理 Agy 上下文、缓存命中率与额度展示组件。
 */
class AgyStatusBarWidgetFactory : StatusBarWidgetFactory {

    /**
     * 返回状态栏控件唯一标识。
     */
    override fun getId(): String = AgyStatusBarWidget.WIDGET_ID

    /**
     * 返回在状态栏右键菜单中显示的本地化名称。
     */
    override fun getDisplayName(): String = AgyBundle.message("statusbar.metrics.displayName")

    /**
     * 仅在插件配置启用状态栏监控且已通过插件启动 Antigravity 终端会话时提供该控件。
     */
    override fun isAvailable(project: Project): Boolean =
        AgySettingsState.getInstance().showStatusBarMetrics &&
            project.getServiceIfCreated(AgyMetricsService::class.java)?.isSessionActive() == true

    /**
     * 创建与当前项目绑定的状态栏文本控件实例。
     */
    override fun createWidget(project: Project): StatusBarWidget = AgyStatusBarWidget(project)

    /**
     * 销毁状态栏控件并释放关联资源。
     */
    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    /**
     * 允许在常规 IDE 底部状态栏中启用该控件。
     */
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
