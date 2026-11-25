package com.github.agylauncher.actions

import com.github.agylauncher.AgyBundle
import com.github.agylauncher.settings.AgySettingsState
import com.github.agylauncher.terminal.AgyTerminalService
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil

/**
 * 提取当前编辑器光标所在行（或选区范围）的报错/警告诊断信息，
 * 并连同文件路径与行号一并发送到 Antigravity CLI 终端的动作。
 */
class SendDiagnosticToAgyAction : AnAction(), DumbAware {

    /**
     * 指定动作状态更新在后台线程执行。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * 更新动作可见性与可用性，仅在打开项目且焦点位于编辑器内时显示并启用。
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && editor != null && file != null
    }

    /**
     * 提取当前行诊断信息，拼接文件位置后发送到 Antigravity 终端；若无诊断则弹出轻量提示。
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val lineRange = resolveTargetLineRange(editor)
        val diagnostics = collectLineDiagnostics(project, editor.document, lineRange.startOffset, lineRange.endOffset)
        if (diagnostics.isEmpty()) {
            HintManager.getInstance().showInformationHint(
                editor,
                AgyBundle.message("action.AgyLauncher.SendDiagnostic.noDiagnostic")
            )
            return
        }

        val settings = AgySettingsState.getInstance()
        val baseRef = AgyFileRefFormatter.formatReference(project, file, settings, lineRange.lineSuffix)
        val diagnosticText = diagnostics.joinToString("; ")
        val payload = AgyFileRefFormatter.applyTrailingSpace("$baseRef [$diagnosticText]", settings)

        AgyTerminalService.getInstance(project).sendTextToAgy(payload)
    }

    /**
     * 计算目标行（或选区起止行）的文档偏移范围与行号后缀。
     */
    private fun resolveTargetLineRange(editor: Editor): TargetLineRange {
        val document = editor.document
        val selectionModel = editor.selectionModel
        val startLine: Int
        val endLine: Int

        if (selectionModel.hasSelection()) {
            startLine = document.getLineNumber(selectionModel.selectionStart)
            val selectionEnd = selectionModel.selectionEnd
            val rawEndLine = document.getLineNumber(selectionEnd)
            // 若选区正好结束于下一行行首，则不计入该空行
            endLine = if (rawEndLine > startLine && selectionEnd == document.getLineStartOffset(rawEndLine)) {
                rawEndLine - 1
            } else {
                rawEndLine
            }
        } else {
            val caretLine = document.getLineNumber(editor.caretModel.offset)
            startLine = caretLine
            endLine = caretLine
        }

        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        val lineSuffix = if (startLine == endLine) {
            "#L${startLine + 1}"
        } else {
            "#L${startLine + 1}-L${endLine + 1}"
        }
        return TargetLineRange(startOffset, endOffset, lineSuffix)
    }

    /**
     * 从文档的 MarkupModel 中读取指定偏移范围内的错误/警告 HighlightInfo 并提取去重后的诊断文案。
     * 若范围内同时存在错误（ERROR）与警告（WARNING），优先返回错误级别诊断。
     */
    private fun collectLineDiagnostics(
        project: Project,
        document: Document,
        rangeStart: Int,
        rangeEnd: Int
    ): List<String> {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val markupModel = DocumentMarkupModel.forDocument(document, project, false)
                ?: return@Computable emptyList()

            val matched = markupModel.allHighlighters
                .asSequence()
                .filter { it.isValid }
                .mapNotNull { HighlightInfo.fromRangeHighlighter(it) }
                .filter { info ->
                    info.endOffset >= rangeStart &&
                        info.startOffset <= rangeEnd &&
                        info.severity >= HighlightSeverity.WARNING
                }
                .sortedBy { it.startOffset }
                .toList()

            if (matched.isEmpty()) {
                return@Computable emptyList()
            }

            val errors = matched.filter { it.severity >= HighlightSeverity.ERROR }
            val candidates = if (errors.isNotEmpty()) errors else matched

            candidates.mapNotNull { extractMessage(it) }
                .distinct()
        })
    }

    /**
     * 提取单个 HighlightInfo 的纯文本描述；若 description 为空则回退解析去标签后的 toolTip。
     */
    private fun extractMessage(info: HighlightInfo): String? {
        val raw = info.description?.takeIf { it.isNotBlank() }
            ?: info.toolTip?.let { StringUtil.removeHtmlTags(it, true) }
            ?: return null

        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        return normalized.ifEmpty { null }
    }

    /**
     * 目标行的文档偏移区间及格式化行号后缀。
     */
    private data class TargetLineRange(
        val startOffset: Int,
        val endOffset: Int,
        val lineSuffix: String
    )
}
