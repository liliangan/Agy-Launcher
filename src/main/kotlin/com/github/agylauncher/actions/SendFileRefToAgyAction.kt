package com.github.agylauncher.actions

import com.github.agylauncher.settings.AgySettingsState
import com.github.agylauncher.terminal.AgyTerminalService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

/**
 * 将选中的文件路径（及可选的编辑器选中行号范围）发送到 Antigravity CLI 终端的动作。
 */
class SendFileRefToAgyAction : AnAction(), DumbAware {

    /**
     * 指定动作状态更新在后台线程执行。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 更新动作可见性与可用性，仅在打开项目且存在选中文件时显示并启用。
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = getSelectedFiles(e)
        e.presentation.isEnabledAndVisible = project != null && files.isNotEmpty()
    }

    /**
     * 收集选中的文件与编辑器选区行号，格式化后发送至 Antigravity 终端。
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val settings = AgySettingsState.getInstance()
        val editor = e.getData(CommonDataKeys.EDITOR)

        val formattedRefs = files.map { file ->
            val lineSuffix = if (files.size == 1 && editor != null && settings.includeLineNumbers) {
                val selectionModel = editor.selectionModel
                if (selectionModel.hasSelection()) {
                    val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1
                    val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1
                    if (startLine == endLine) "#L$startLine" else "#L$startLine-L$endLine"
                } else {
                    ""
                }
            } else {
                ""
            }
            AgyFileRefFormatter.formatReference(project, file, settings, lineSuffix)
        }

        val joined = formattedRefs.joinToString(" ")
        val finalPayload = AgyFileRefFormatter.applyTrailingSpace(joined, settings)

        AgyTerminalService.getInstance(project).sendTextToAgy(finalPayload)
    }

    /**
     * 从动作上下文中提取当前选中的单文件或多文件列表。
     */
    private fun getSelectedFiles(e: AnActionEvent): List<VirtualFile> {
        val multiFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!multiFiles.isNullOrEmpty()) {
            return multiFiles.toList()
        }
        val singleFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (singleFile != null) listOf(singleFile) else emptyList()
    }
}
