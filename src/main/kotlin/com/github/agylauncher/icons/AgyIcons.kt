package com.github.agylauncher.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Antigravity Launcher 插件图标定义。
 * 通过 IconLoader 加载，平台会自动根据当前浅色/深色主题匹配 agy.svg 与 agy_dark.svg。
 */
object AgyIcons {

    /** 插件主图标（用于工具栏按钮与菜单项） */
    @JvmField
    val Agy: Icon = IconLoader.getIcon("/icons/agy.svg", AgyIcons::class.java)
}
