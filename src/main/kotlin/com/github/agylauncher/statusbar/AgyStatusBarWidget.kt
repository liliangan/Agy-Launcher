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
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 底部状态栏自定义监控控件。
 * 第一行展示上下文占用率与命中缓存；第二行独立展示 5h 与周剩余额度；
 * 悬停展示全部配额池明细与刷新倒计时，支持左键 `/context` 刷新与右键 `/compact` 压缩菜单。
 */
class AgyStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    /** 鼠标交互适配器：左键刷新上下文，右键弹出快捷操作菜单 */
    private val mouseInteractionListener = object : MouseAdapter() {
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
    }

    /** 第一行状态标签：上下文与缓存命中率 */
    private val metricsLabel: JBLabel = createHoverableLabel(AgyMetricsService.defaultDisplayText())

    /** 第二行状态标签：独立显示的 5h 与周剩余额度 */
    private val quotaLabel: JBLabel = createHoverableLabel(AgyMetricsService.defaultQuotaText())

    /** 垂直两行容器面板，避免上下文信息与额度信息挤在同一行 */
    private val containerPanel: JPanel = object : JPanel() {
        /**
         * 动态返回当前最新指标与全部额度的悬停明细提示。
         */
        override fun getToolTipText(): String =
            AgyMetricsService.getInstance(project).getTooltipText()
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(1, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(metricsLabel)
        add(quotaLabel)
        addMouseListener(mouseInteractionListener)
    }

    private val metricsChangeListener: () -> Unit = { syncDisplayLabel() }

    /**
     * 创建统一样式与悬停/点击行为的状态栏单行标签。
     */
    private fun createHoverableLabel(initialText: String): JBLabel =
        object : JBLabel(initialText) {
            /**
             * 动态返回当前最新指标与全部额度的悬停明细提示。
             */
            override fun getToolTipText(): String =
                AgyMetricsService.getInstance(project).getTooltipText()
        }.apply {
            font = deriveCompactStatusBarFont()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(mouseInteractionListener)
        }

    /**
     * 计算适合两行堆叠展示的紧凑状态栏字体，避免过度撑高底部状态栏。
     */
    private fun deriveCompactStatusBarFont(): Font {
        val baseFont = JBUI.CurrentTheme.StatusBar.font()
        val compactSize = maxOf(9.5f, baseFont.size2D - 2.0f)
        return baseFont.deriveFont(compactSize)
    }

    /**
     * 返回状态栏控件唯一标识。
     */
    override fun ID(): String = WIDGET_ID

    /**
     * 返回用于挂载到状态栏的双行 Swing 容器，并在每次状态栏刷新时同步最新文本。
     */
    override fun getComponent(): JComponent {
        syncDisplayLabel()
        return containerPanel
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
     * 从指标服务读取最新上下文行与 5h 额度行文案，并按需刷新布局与重绘。
     */
    private fun syncDisplayLabel() {
        val service = AgyMetricsService.getInstance(project)
        val metricsText = service.getDisplayLabel()
        val quotaText = service.getQuotaLabel()
        var changed = false
        if (metricsLabel.text != metricsText) {
            metricsLabel.text = metricsText
            changed = true
        }
        if (quotaLabel.text != quotaText) {
            quotaLabel.text = quotaText
            changed = true
        }
        if (changed) {
            containerPanel.revalidate()
            containerPanel.repaint()
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
        popup.show(RelativePoint(containerPanel, showPoint))
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
