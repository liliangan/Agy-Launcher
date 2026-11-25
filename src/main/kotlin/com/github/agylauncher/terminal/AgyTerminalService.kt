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

@Service(Service.Level.PROJECT)
class AgyTerminalService(private val project: Project) {

    private val logger = Logger.getInstance(AgyTerminalService::class.java)

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
        val widget = manager.createLocalShellWidget(workingDir, settings.terminalTabTitle)
        val fullCommand = buildLaunchCommand(settings)

        try {
            widget.executeCommand(fullCommand)
        } catch (e: IOException) {
            logger.warn("Failed to execute agy command: $fullCommand", e)
        }
    }

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
        val widget = manager.createLocalShellWidget(workingDir, settings.terminalTabTitle)
        val fullCommand = buildLaunchCommand(settings)

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

    private fun findAgyContent(manager: TerminalToolWindowManager, targetTitle: String): Content? {
        val contentManager = manager.toolWindow?.contentManager ?: return null
        return contentManager.contents.firstOrNull { content ->
            content.displayName.equals(targetTitle, ignoreCase = true)
        }
    }

    private fun buildLaunchCommand(settings: AgySettingsState): String {
        val cmd = settings.commandPath.trim().ifEmpty { "agy" }
        val args = settings.defaultArgs.trim()
        return if (args.isEmpty()) cmd else "$cmd $args"
    }

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
        fun getInstance(project: Project): AgyTerminalService =
            project.getService(AgyTerminalService::class.java)
    }
}
