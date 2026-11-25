package com.github.agylauncher.terminal

import com.github.agylauncher.settings.AgySettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Antigravity 内置终端管理服务。
 * 负责启动/聚焦目标终端标签页、向终端写入指令与上下文，以及查找活跃的终端控件。
 */
@Service(Service.Level.PROJECT)
class AgyTerminalService(private val project: Project) {

    private val logger = Logger.getInstance(AgyTerminalService::class.java)
    private var trackedWidgetRef: WeakReference<ShellTerminalWidget>? = null

    /**
     * 在内置终端中启动或聚焦 Antigravity CLI 标签页。
     */
    fun launchOrFocus() {
        val settings = AgySettingsState.getInstance()
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

        val existingContent = findAgyContent(manager, settings.terminalTabTitle)
        if (existingContent != null) {
            toolWindow?.activate {
                manager.toolWindow?.contentManager?.setSelectedContent(existingContent, true)
            }
            return
        }

        val workingDir = project.basePath ?: System.getProperty("user.home")
        val metricsService = AgyMetricsService.getInstance(project)
        metricsService.suppressStartupWindow()
        val widget = manager.createLocalShellWidget(workingDir, settings.terminalTabTitle)
        trackedWidgetRef = WeakReference(widget)
        val fullCommand = buildLaunchCommand(settings, metricsService)

        try {
            widget.executeCommand(fullCommand)
        } catch (e: IOException) {
            logger.warn("Failed to execute agy command: $fullCommand", e)
        }
    }

    /**
     * 向 Antigravity 终端写入文本；若未启动且配置允许自动拉起，则先启动 CLI 再延迟写入。
     */
    fun sendTextToAgy(text: String) {
        if (text.isBlank()) return
        val settings = AgySettingsState.getInstance()
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

        val existingContent = findAgyContent(manager, settings.terminalTabTitle)
        if (existingContent != null) {
            toolWindow?.activate {
                manager.toolWindow?.contentManager?.setSelectedContent(existingContent, true)
                val widget = TerminalToolWindowManager.getWidgetByContent(existingContent)
                if (widget is ShellTerminalWidget) {
                    writeRawToTty(widget, text)
                }
            }
            return
        }

        if (!settings.autoStartIfNotRunning) return

        val workingDir = project.basePath ?: System.getProperty("user.home")
        val metricsService = AgyMetricsService.getInstance(project)
        metricsService.suppressStartupWindow()
        val widget = manager.createLocalShellWidget(workingDir, settings.terminalTabTitle)
        trackedWidgetRef = WeakReference(widget)
        val fullCommand = buildLaunchCommand(settings, metricsService)

        try {
            widget.executeCommand(fullCommand)
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(1200)
                ApplicationManager.getApplication().invokeLater {
                    writeRawToTty(widget, text)
                }
            }
        } catch (e: IOException) {
            logger.warn("Failed to start agy and send text", e)
        }
    }

    /**
     * 查找当前已打开的目标 Antigravity 终端控件，不激活工具窗口也不新建终端。
     */
    fun findAgyTerminalWidget(): ShellTerminalWidget? {
        val settings = AgySettingsState.getInstance()
        val manager = TerminalToolWindowManager.getInstance(project)
        val existingContent = findAgyContent(manager, settings.terminalTabTitle) ?: return null
        return TerminalToolWindowManager.getWidgetByContent(existingContent) as? ShellTerminalWidget
    }

    /**
     * 向当前活跃的 Antigravity 终端静默发送 `/context\r` 命令以刷新上下文统计。
     */
    fun sendContextCommand(): Boolean = sendRawToActiveTerminal("/context\r")

    /**
     * 向当前活跃的 Antigravity 终端发送 `/compact\r` 命令以触发上下文压缩。
     */
    fun sendCompactCommand(): Boolean = sendRawToActiveTerminal("/compact\r")

    /**
     * 向当前活跃的 Antigravity 终端发送 ESC 控制符以关闭上下文弹窗面板。
     */
    fun sendEscapeCommand(): Boolean = sendRawToActiveTerminal("\u001B")

    /**
     * 在不激活或新建终端标签页的前提下，向已存在的目标终端发送原始控制序列或指令。
     */
    private fun sendRawToActiveTerminal(rawText: String): Boolean {
        val widget = findAgyTerminalWidget() ?: return false
        writeRawToTty(widget, rawText)
        return true
    }

    /**
     * 按标签页标题或已追踪的控件引用查找匹配的终端 Content，兼容 CLI 运行期间动态修改标签页标题的情况。
     */
    private fun findAgyContent(manager: TerminalToolWindowManager, targetTitle: String): Content? {
        val contentManager = manager.toolWindow?.contentManager ?: return null
        val contents = contentManager.contents
        val exactMatch = contents.firstOrNull { it.displayName.equals(targetTitle, ignoreCase = true) }
        if (exactMatch != null) {
            val widget = TerminalToolWindowManager.getWidgetByContent(exactMatch) as? ShellTerminalWidget
            if (widget != null) {
                trackedWidgetRef = WeakReference(widget)
            }
            return exactMatch
        }

        val tracked = trackedWidgetRef?.get()
        if (tracked != null) {
            val byWidget = contents.firstOrNull { TerminalToolWindowManager.getWidgetByContent(it) === tracked }
            if (byWidget != null) {
                return byWidget
            }
        }

        return contents.firstOrNull { it.displayName.contains(targetTitle, ignoreCase = true) }
    }

    /**
     * 拼装完整的 CLI 启动命令；开启状态栏监控且未手动指定 `--settings` 时附加会话级 statusLine 配置以采集缓存命中率。
     */
    private fun buildLaunchCommand(
        settings: AgySettingsState,
        metricsService: AgyMetricsService
    ): String {
        val cmd = settings.commandPath.trim().ifEmpty { "agy" }
        val args = settings.defaultArgs.trim()
        val baseCmd = if (args.isEmpty()) cmd else "$cmd $args"
        if (!settings.showStatusBarMetrics || args.contains("--settings")) {
            return baseCmd
        }
        val sessionSettingsPath = metricsService.prepareSessionSettingsFile() ?: return baseCmd
        val escapedPath = sessionSettingsPath.replace("\"", "\\\"")
        return "$baseCmd --settings \"$escapedPath\""
    }

    /**
     * 将原始文本写入终端 TTY 连接。
     */
    private fun writeRawToTty(widget: ShellTerminalWidget, text: String) {
        try {
            val connector = widget.ttyConnector
            if (connector != null && connector.isConnected) {
                connector.write(text)
            } else {
                widget.terminalStarter?.sendString(text, false)
            }
        } catch (e: Exception) {
            logger.warn("Failed to write text to terminal TTY", e)
        }
    }

    companion object {
        /**
         * 获取项目级的终端管理服务实例。
         */
        fun getInstance(project: Project): AgyTerminalService =
            project.getService(AgyTerminalService::class.java)
    }
}
