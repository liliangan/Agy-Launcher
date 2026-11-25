package com.github.agylauncher.actions

import com.github.agylauncher.terminal.AgyTerminalService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * 在 WebStorm 内置终端中启动或聚焦 Antigravity CLI 的动作。
 */
class LaunchAgyAction : AnAction(), DumbAware {

    /**
     * 指定动作状态更新在后台线程执行。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 更新动作的可见性与可用状态，确保工具栏与菜单入口始终可见，仅在打开项目时可点击。
     */
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null
    }

    /**
     * 触发启动或聚焦 Antigravity 终端标签页。
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AgyTerminalService.getInstance(project).launchOrFocus()
    }
}
