package com.github.agylauncher.actions

import com.github.agylauncher.settings.AgySettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Antigravity 文件引用格式化工具。
 * 统一处理项目相对路径解析、含空格路径引号包裹、@ 前缀及末尾空格拼接。
 */
object AgyFileRefFormatter {

    /**
     * 根据设置将虚拟文件格式化为带可选前缀与行号后缀的引用字符串。
     */
    fun formatReference(
        project: Project,
        file: VirtualFile,
        settings: AgySettingsState,
        lineSuffix: String = ""
    ): String {
        var pathStr = resolvePath(project, file, settings.useRelativePath)
        if (pathStr.contains(" ")) {
            pathStr = "\"$pathStr\""
        }
        val prefix = if (settings.useAtPrefix) "@" else ""
        return "$prefix$pathStr$lineSuffix"
    }

    /**
     * 按配置决定是否在发送文本末尾追加空格。
     */
    fun applyTrailingSpace(text: String, settings: AgySettingsState): String =
        if (settings.appendTrailingSpace) "$text " else text

    /**
     * 解析目标文件的路径字符串，支持返回相对项目根目录的路径或绝对路径。
     */
    fun resolvePath(project: Project, file: VirtualFile, useRelative: Boolean): String {
        if (!useRelative) {
            return file.path
        }
        val basePath = project.basePath ?: return file.path
        val baseDir = File(basePath)
        val targetFile = File(file.path)
        return try {
            val projectDir = project.guessProjectDir()
            val relative = if (projectDir != null) {
                VfsUtilCore.getRelativePath(file, projectDir)
            } else {
                null
            } ?: targetFile.relativeTo(baseDir).path
            relative.replace('\\', '/')
        } catch (_: Exception) {
            file.path
        }
    }
}
