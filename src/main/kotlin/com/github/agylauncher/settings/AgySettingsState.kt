package com.github.agylauncher.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Agy Launcher 插件应用级持久化配置状态。
 */
@Service(Service.Level.APP)
@State(
    name = "com.github.agylauncher.settings.AgySettingsState",
    storages = [Storage("AntigravityLauncher.xml")]
)
class AgySettingsState : PersistentStateComponent<AgySettingsState> {

    /** agy 可执行命令路径 */
    var commandPath: String = "agy"

    /** 启动 agy 时默认附加的命令行参数 */
    var defaultArgs: String = ""

    /** 内置终端标签页名称 */
    var terminalTabTitle: String = "Antigravity"

    /** 是否在文件路径前添加 @ 前缀 */
    var useAtPrefix: Boolean = false

    /** 是否优先使用相对项目的路径 */
    var useRelativePath: Boolean = true

    /** 从编辑器触发时是否附带选中行号范围 */
    var includeLineNumbers: Boolean = true

    /** 插入引用或诊断文本后是否在末尾追加空格 */
    var appendTrailingSpace: Boolean = true

    /** 未检测到目标终端标签页时是否自动启动 agy */
    var autoStartIfNotRunning: Boolean = true

    /** 是否在底部状态栏展示上下文、命中缓存与额度监控 */
    var showStatusBarMetrics: Boolean = true

    /**
     * 返回当前需要持久化的配置状态实例。
     */
    override fun getState(): AgySettingsState = this

    /**
     * 从磁盘加载持久化配置到当前状态对象。
     */
    override fun loadState(state: AgySettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        /**
         * 获取全局唯一的插件配置服务实例。
         */
        fun getInstance(): AgySettingsState =
            ApplicationManager.getApplication().getService(AgySettingsState::class.java)
    }
}
