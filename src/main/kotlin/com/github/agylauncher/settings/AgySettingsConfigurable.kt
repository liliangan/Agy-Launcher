package com.github.agylauncher.settings

import com.github.agylauncher.AgyBundle
import com.github.agylauncher.statusbar.AgyStatusBarWidgetFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Agy Launcher 插件设置面板配置类。
 */
class AgySettingsConfigurable : BoundConfigurable(AgyBundle.message("configurable.AgySettings.displayName")) {

    private val settings = AgySettingsState.getInstance()

    /**
     * 构建插件设置界面布局并绑定持久化配置项。
     */
    override fun createPanel(): DialogPanel = panel {
        group(AgyBundle.message("settings.group.cliExecution")) {
            row(AgyBundle.message("settings.cli.executable.label")) {
                textField()
                    .bindText(settings::commandPath)
                    .align(AlignX.FILL)
                    .comment(AgyBundle.message("settings.cli.executable.comment"))
            }
            row(AgyBundle.message("settings.cli.defaultArgs.label")) {
                textField()
                    .bindText(settings::defaultArgs)
                    .align(AlignX.FILL)
                    .comment(AgyBundle.message("settings.cli.defaultArgs.comment"))
            }
            row(AgyBundle.message("settings.cli.tabTitle.label")) {
                textField()
                    .bindText(settings::terminalTabTitle)
                    .align(AlignX.FILL)
            }
        }

        group(AgyBundle.message("settings.group.fileReference")) {
            row {
                checkBox(AgyBundle.message("settings.ref.useAtPrefix"))
                    .bindSelected(settings::useAtPrefix)
            }
            row {
                checkBox(AgyBundle.message("settings.ref.useRelativePath"))
                    .bindSelected(settings::useRelativePath)
            }
            row {
                checkBox(AgyBundle.message("settings.ref.includeLineNumbers"))
                    .bindSelected(settings::includeLineNumbers)
            }
            row {
                checkBox(AgyBundle.message("settings.ref.appendTrailingSpace"))
                    .bindSelected(settings::appendTrailingSpace)
            }
            row {
                checkBox(AgyBundle.message("settings.ref.autoStartIfNotRunning"))
                    .bindSelected(settings::autoStartIfNotRunning)
            }
        }

        group(AgyBundle.message("settings.group.statusBarMonitor")) {
            row {
                checkBox(AgyBundle.message("settings.monitor.showStatusBarMetrics"))
                    .bindSelected(settings::showStatusBarMetrics)
            }
        }
    }

    /**
     * 保存配置后立即刷新所有打开项目的状态栏监控组件显隐状态。
     */
    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { openProject ->
            if (!openProject.isDisposed) {
                openProject.getService(StatusBarWidgetsManager::class.java)
                    ?.updateWidget(AgyStatusBarWidgetFactory::class.java)
            }
        }
    }
}
