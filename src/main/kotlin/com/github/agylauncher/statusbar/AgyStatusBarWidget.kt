package com.github.agylauncher.statusbar

import com.github.agylauncher.AgyBundle
import com.github.agylauncher.terminal.AgyMetricsService
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * 底部状态栏自定义监控控件。
 * 展示 Antigravity CLI 上下文占用率、阈值预警与输出速度；
 * 支持左键单击刷新 `/context`（并自动关闭弹窗）以及右键弹出操作菜单执行 `/compact` 压缩。
 */
class AgyStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val label: JBLabel = object : JBLabel(AgyMetricsService.defaultDisplayText()) {
        /**
         * 动态返回当前最新指标的悬停明细提示。
         */
        override fun getToolTipText(): String =
            AgyMetricsService.getInstance(project).getTooltipText()
    }.apply {
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val statusBarFont = JBUI.CurrentTheme.StatusBar.font()
        font = statusBarFont
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handlePopupTrigger(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (handlePopupTrigger(e)) return
                if (SwingUtilities.isLeftMouseButton(e) && e.component.contains(e.point)) {
                    e.consume()
                    AgyMetricsService.getInstance(project).triggerContextRefresh(autoDismiss = true)
                }
            }
        })
    }

    private val metricsChangeListener: () -> Unit = { syncDisplayLabel() }

    /**
     * 返回状态栏控件唯一标识。
     */
    override fun ID(): String = WIDGET_ID

    /**
     * 返回用于挂载到状态栏的 Swing 组件，并在每次状态栏刷新时同步最新文本。
     */
    override fun getComponent(): JComponent {
        syncDisplayLabel()
        return label
    }

    /**
     * 安装到状态栏时启动对应项目的后台终端缓冲区轮询服务，并订阅文本变化通知。
     */
    override fun install(statusBar: StatusBar) {
        val metricsService = AgyMetricsService.getInstance(project)
        metricsService.addChangeListener(metricsChangeListener)
        metricsService.ensureStarted()
        syncDisplayLabel()
    }

    /**
     * 释放控件资源并移除监听器。
     */
    override fun dispose() {
        if (!project.isDisposed) {
            AgyMetricsService.getInstance(project).removeChangeListener(metricsChangeListener)
        }
    }

    /**
     * 从指标服务读取最新展示文案并更新状态栏标签。
     */
    private fun syncDisplayLabel() {
        val text = AgyMetricsService.getInstance(project).getDisplayLabel()
        if (label.text != text) {
            label.text = text
            label.revalidate()
            label.repaint()
        }
    }

    /**
     * 处理鼠标右键/弹出触发事件，阻止外层状态栏默认菜单并展示快捷操作列表。
     */
    private fun handlePopupTrigger(e: MouseEvent): Boolean {
        if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) {
            e.consume()
            if (e.isPopupTrigger) {
                showContextActionPopup(e)
            }
            return true
        }
        return false
    }

    /**
     * 在状态栏组件上方弹出包含刷新上下文与压缩上下文的操作菜单。
     */
    private fun showContextActionPopup(e: MouseEvent) {
        val items = listOf(
            StatusMenuItem(
                label = AgyBundle.message("statusbar.menu.refreshContext"),
                icon = AllIcons.Actions.Refresh,
                onSelect = { AgyMetricsService.getInstance(project).triggerContextRefresh(autoDismiss = true) }
            ),
            StatusMenuItem(
                label = AgyBundle.message("statusbar.menu.compactContext"),
                icon = AllIcons.Actions.GC,
                onSelect = { AgyMetricsService.getInstance(project).triggerCompact() }
            )
        )

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is StatusMenuItem) {
                        text = value.label
                        icon = value.icon
                    }
                    border = JBUI.Borders.empty(4, 8)
                    return comp
                }
            })
            .setItemChosenCallback { chosen -> chosen.onSelect() }
            .createPopup()

        val popupSize = popup.content.preferredSize
        val showPoint = Point(0, -popupSize.height - JBUI.scale(4))
        popup.show(RelativePoint(e.component, showPoint))
    }

    /**
     * 状态栏右键操作菜单条目模型。
     */
    private data class StatusMenuItem(
        val label: String,
        val icon: Icon,
        val onSelect: () -> Unit
    )

    companion object {
        const val WIDGET_ID = "AgyLauncher.StatusBarMetrics"
    }
}
