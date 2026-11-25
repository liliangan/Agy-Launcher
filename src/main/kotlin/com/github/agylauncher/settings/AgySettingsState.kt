package com.github.agylauncher.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.github.agylauncher.settings.AgySettingsState",
    storages = [Storage("AntigravityLauncher.xml")]
)
class AgySettingsState : PersistentStateComponent<AgySettingsState> {

    var commandPath: String = "agy"
    var defaultArgs: String = ""
    var terminalTabTitle: String = "Antigravity"
    var useAtPrefix: Boolean = false
    var useRelativePath: Boolean = true
    var includeLineNumbers: Boolean = true
    var appendTrailingSpace: Boolean = true
    var autoStartIfNotRunning: Boolean = true

    override fun getState(): AgySettingsState = this

    override fun loadState(state: AgySettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): AgySettingsState =
            ApplicationManager.getApplication().getService(AgySettingsState::class.java)
    }
}
