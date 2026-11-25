package com.github.agylauncher

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.AgyBundle"

/**
 * Antigravity Launcher 插件国际化资源加载器。
 * 根据当前 IDE 语言环境自动读取默认英文或简体中文（_zh_CN）文案。
 */
object AgyBundle : DynamicBundle(BUNDLE) {

    /**
     * 根据资源键获取本地化文案，并按需替换格式化参数。
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
