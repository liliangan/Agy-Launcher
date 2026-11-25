package com.github.agylauncher.terminal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.github.agylauncher.AgyBundle
import com.github.agylauncher.settings.AgySettingsState
import com.github.agylauncher.statusbar.AgyStatusBarWidget
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * Antigravity 终端状态栏指标被动轮询服务。
 * 每 500ms 在后台读取目标终端屏幕缓冲区（最近 30 行历史 + 当前可视屏），
 * 仅从真实的 Context Usage 面板解析上下文占用率，过滤启动横幅、历史回放与 TUI 框线后估算输出速度。
 */
@Service(Service.Level.PROJECT)
class AgyMetricsService(private val project: Project) : Disposable {

    private val cachedWidgetRef = AtomicReference<WeakReference<ShellTerminalWidget>?>(null)
    private val displayTextRef = AtomicReference(defaultDisplayText())
    private val tooltipTextRef = AtomicReference(AgyBundle.message("statusbar.metrics.tooltipDefault"))
    private val pollingTaskRef = AtomicReference<ScheduledFuture<*>?>(null)
    private val started = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val stateLock = Any()
    private var previousLines: List<String>? = null
    private var contextPercentValue: Double? = null
    private var contextPercentText: String? = null
    private var contextDetailText: String? = null
    private var cachedPercentText: String? = null
    private var tokPerSecText: String? = null
    private var isStreaming: Boolean = false
    private var burstTicks: Int = 0
    private var burstStartMs: Long = 0L
    private var lastActiveMs: Long = 0L
    private var accumulatedChars: Long = 0L
    private var lastObservedWidget: ShellTerminalWidget? = null

    private var suppressSpeedUntilMs: Long = 0L
    private var awaitingContextDismiss: Boolean = false
    private var contextDismissDeadlineMs: Long = 0L
    private var contextSentAtMs: Long = 0L
    private var compactPendingRefresh: Boolean = false
    private var compactRefreshTriggerAtMs: Long = 0L
    private var pendingResumeRefresh: Boolean = false
    private var resumeStableSinceMs: Long = 0L
    private var lastResumeMarkerHash: Int = 0
    private var sawExternalModal: Boolean = false
    private var wasUsingAltBuffer: Boolean = false
    private var preModalBodyLines: List<String> = emptyList()
    private var lastMainPromptBodyLines: List<String> = emptyList()
    private var hasSeenMainPrompt: Boolean = false
    private var lastMetricsFileMtime: Long = 0L
    private var lastTotalOutputTokens: Long? = null
    private var lastTotalApiDurationMs: Long? = null
    private var exactTurnSpeedActive: Boolean = false

    private val sessionKey: String = Integer.toHexString((project.basePath ?: project.name).hashCode())
    private val metricsOutputFile: File = File(System.getProperty("java.io.tmpdir"), "agy-launcher-metrics-$sessionKey.json")
    private val sessionSettingsFile: File = File(System.getProperty("java.io.tmpdir"), "agy-launcher-settings-$sessionKey.json")

    /**
     * 确保后台 500ms 轮询任务已启动（幂等）。
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        refreshWidgetReferenceAsync()
        val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { pollSafely() },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        pollingTaskRef.set(future)
    }

    /**
     * 获取当前应在状态栏展示的文本（例如 `Agy: Ctx 0.0% | -- tok/s`）。
     */
    fun getDisplayLabel(): String = displayTextRef.get()

    /**
     * 获取鼠标悬停时的详细指标与操作提示文本。
     */
    fun getTooltipText(): String = tooltipTextRef.get()

    /**
     * 生成会话级临时 settings.json，配置 `agy` 的 `statusLine` 将官方上下文与缓存 JSON 实时写入临时指标文件。
     */
    fun prepareSessionSettingsFile(): String? {
        return try {
            metricsOutputFile.delete()
            lastMetricsFileMtime = 0L

            val outputPath = metricsOutputFile.absolutePath
            val tmpPath = "$outputPath.tmp"
            val command = if (SystemInfo.isWindows) {
                val psOut = outputPath.replace("'", "''")
                val psTmp = tmpPath.replace("'", "''")
                "powershell -NoProfile -Command \"\$input | Set-Content -LiteralPath '$psTmp' -Encoding UTF8; Move-Item -Force '$psTmp' '$psOut'\""
            } else {
                val shOut = outputPath.replace("'", "'\"'\"'")
                val shTmp = tmpPath.replace("'", "'\"'\"'")
                "sh -c 'cat > \"$shTmp\" && mv -f \"$shTmp\" \"$shOut\"'"
            }

            val root = JsonObject()
            val statusLine = JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", command)
                addProperty("enabled", true)
                addProperty("stack_with_default", true)
                addProperty("padding", 0)
            }
            root.add("statusLine", statusLine)

            Files.writeString(sessionSettingsFile.toPath(), root.toString(), StandardCharsets.UTF_8)
            sessionSettingsFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 在新建终端并启动 CLI 时进入启动静默窗口，避免将启动横幅与初始化文本误计为输出速度。
     */
    fun suppressStartupWindow() {
        val nowMs = System.currentTimeMillis()
        synchronized(stateLock) {
            suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + STARTUP_SPEED_SUPPRESS_MS)
            sawExternalModal = false
            pendingResumeRefresh = false
            clearContextMetrics()
            resetPendingBurst()
        }
        metricsOutputFile.delete()
        lastMetricsFileMtime = 0L
        synchronized(stateLock) {
            lastTotalOutputTokens = null
            lastTotalApiDurationMs = null
            exactTurnSpeedActive = false
        }
    }

    /**
     * 触发 `/context\r` 刷新上下文占用率；若开启自动关闭，读取到新面板数值后自动发送 ESC 关闭弹层。
     */
    fun triggerContextRefresh(autoDismiss: Boolean = true) {
        val nowMs = System.currentTimeMillis()
        synchronized(stateLock) {
            suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + COMMAND_SPEED_SUPPRESS_MS)
            contextSentAtMs = nowMs
            if (autoDismiss) {
                awaitingContextDismiss = true
                contextDismissDeadlineMs = nowMs + CONTEXT_DISMISS_TIMEOUT_MS
            }
        }
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                AgyTerminalService.getInstance(project).sendContextCommand()
            }
        }, ModalityState.any())
    }

    /**
     * 向 Antigravity 终端发送 `/compact\r` 触发上下文压缩；若未启用 statusLine 遥测，则在压缩结束后兜底刷新一次 `/context`。
     */
    fun triggerCompact() {
        val nowMs = System.currentTimeMillis()
        synchronized(stateLock) {
            suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + COMPACT_SPEED_SUPPRESS_MS)
            awaitingContextDismiss = false
            compactPendingRefresh = !metricsOutputFile.exists()
            compactRefreshTriggerAtMs = nowMs + COMPACT_REFRESH_DELAY_MS
            resetPendingBurst()
        }
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                AgyTerminalService.getInstance(project).sendCompactCommand()
            }
        }, ModalityState.any())
        notifyStatusBarUpdate()
    }

    /**
     * 注册指标文本变化监听器，用于在 EDT 上同步更新自定义状态栏控件。
     */
    fun addChangeListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /**
     * 注销指标文本变化监听器。
     */
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 释放服务时取消后台定时轮询任务并清理缓存引用及临时配置文件。
     */
    override fun dispose() {
        pollingTaskRef.getAndSet(null)?.cancel(false)
        cachedWidgetRef.set(null)
        listeners.clear()
        runCatching { metricsOutputFile.delete() }
        runCatching { sessionSettingsFile.delete() }
    }

    /**
     * 异步在 EDT 上刷新对 Antigravity 终端控件的弱引用，避免后台线程直接遍历 Swing 容器。
     */
    private fun refreshWidgetReferenceAsync() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val widget = AgyTerminalService.getInstance(project).findAgyTerminalWidget()
            cachedWidgetRef.set(widget?.let { WeakReference(it) })
        }, ModalityState.any())
    }

    /**
     * 安全执行单次缓冲区轮询，协调上下文解析、弹窗自动关闭、会话恢复同步与速度计算。
     */
    private fun pollSafely() {
        if (project.isDisposed) return
        val settings = AgySettingsState.getInstance()
        if (!settings.showStatusBarMetrics) return

        refreshWidgetReferenceAsync()
        val statusLineTelemetry = readStatusLineTelemetryIfUpdated()
        val widget = cachedWidgetRef.get()?.get()
        if (widget == null && statusLineTelemetry == null) return
        val lines = widget?.let { readRecentTerminalLines(it) } ?: emptyList()

        val nowMs = System.currentTimeMillis()
        var shouldSendEscape = false
        var shouldRefreshContext = false
        val newDisplayText: String
        val newTooltipText: String

        synchronized(stateLock) {
            if (statusLineTelemetry != null) {
                statusLineTelemetry.contextUsage?.let { usage ->
                    contextPercentValue = usage.percent
                    contextPercentText = usage.percentText
                    contextDetailText = usage.detailText
                }
                statusLineTelemetry.cacheHitPercentText?.let { cachePct ->
                    cachedPercentText = cachePct
                }
                applyExactOutputSpeedFromTelemetry(statusLineTelemetry, nowMs)
            }

            val isNewWidget = widget != null && lastObservedWidget !== widget
            if (isNewWidget) {
                lastObservedWidget = widget
                suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + STARTUP_SPEED_SUPPRESS_MS)
                lastResumeMarkerHash = 0
                sawExternalModal = false
                wasUsingAltBuffer = false
                hasSeenMainPrompt = false
                lastMainPromptBodyLines = emptyList()
                preModalBodyLines = emptyList()
                pendingResumeRefresh = false
                resetPendingBurst()
            }

            val usingAltBuffer = widget?.let { isAlternateBufferActive(it) } ?: false
            val hasContextPanel = containsContextPanel(lines)
            val hasOtherModal = !hasContextPanel && (usingAltBuffer || hasModalOverlay(lines))
            val atMainPrompt = isAtMainPrompt(lines) && !hasContextPanel && !hasOtherModal

            val parsedContext = if (hasContextPanel) extractLatestContextUsage(lines) else null
            if (parsedContext != null) {
                contextPercentValue = parsedContext.percent
                contextPercentText = parsedContext.percentText
                contextDetailText = parsedContext.detailText
            }

            val latestCache = extractLatestCachePercent(lines)
            if (latestCache != null) {
                cachedPercentText = latestCache
            }

            // 读取到 /context 面板且处于等待自动关闭状态时，发送 ESC 退出弹窗；超时则重置状态
            if (awaitingContextDismiss) {
                if (nowMs >= contextDismissDeadlineMs) {
                    awaitingContextDismiss = false
                } else if (nowMs > contextSentAtMs && hasContextPanel && parsedContext != null) {
                    awaitingContextDismiss = false
                    suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + DISMISS_SPEED_SUPPRESS_MS)
                    shouldSendEscape = true
                }
            }

            val prev = previousLines
            val deltaChars = if (prev != null && !isNewWidget) computeAddedVisibleChars(prev, lines) else 0
            val addedLinesHasUiBanner = prev != null && hasNonStreamUiBannerInDelta(prev, lines)
            previousLines = lines

            val currentBodyLines = extractConversationBody(lines)

            // 跟踪外部弹窗（如 /resume 会话选择列表）或备用屏幕缓冲区切换
            if ((hasOtherModal || (usingAltBuffer && !wasUsingAltBuffer)) && !awaitingContextDismiss) {
                if (!sawExternalModal) {
                    sawExternalModal = true
                    preModalBodyLines = lastMainPromptBodyLines
                }
                suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + COMMAND_SPEED_SUPPRESS_MS)
            }
            wasUsingAltBuffer = usingAltBuffer

            val resumeSignature = computeResumeSignature(lines)
            val resumeMarkerTriggered = resumeSignature != 0 && resumeSignature != lastResumeMarkerHash
            val suddenHistoryJump = !isNewWidget &&
                hasSeenMainPrompt &&
                !isStreaming &&
                !awaitingContextDismiss &&
                atMainPrompt &&
                deltaChars >= SUDDEN_HISTORY_JUMP_CHARS

            val modalChangedConversation = sawExternalModal &&
                atMainPrompt &&
                hasBodyReplaced(preModalBodyLines, currentBodyLines, fromModal = true)

            val promptBodySwitched = !isNewWidget &&
                hasSeenMainPrompt &&
                atMainPrompt &&
                !awaitingContextDismiss &&
                !isStreaming &&
                hasBodyReplaced(lastMainPromptBodyLines, currentBodyLines)

            // 仅当尚未拿到 statusLine 结构化遥测且检测到 /resume 等会话切换时，才触发一次 /context 兜底
            if (statusLineTelemetry == null && (resumeMarkerTriggered || suddenHistoryJump || modalChangedConversation || promptBodySwitched)) {
                if (resumeSignature != 0) {
                    lastResumeMarkerHash = resumeSignature
                }
                sawExternalModal = false
                if (!metricsOutputFile.exists()) {
                    pendingResumeRefresh = true
                    resumeStableSinceMs = nowMs
                    clearContextMetrics()
                }
                suppressSpeedUntilMs = maxOf(suppressSpeedUntilMs, nowMs + COMPACT_SPEED_SUPPRESS_MS)
                resetPendingBurst()
            } else if (sawExternalModal && atMainPrompt) {
                sawExternalModal = false
            }

            if (atMainPrompt) {
                hasSeenMainPrompt = true
                lastMainPromptBodyLines = currentBodyLines
            }

            if (pendingResumeRefresh) {
                if (atMainPrompt && deltaChars < MIN_DELTA_CHARS) {
                    if (nowMs - resumeStableSinceMs >= RESUME_REFRESH_DELAY_MS) {
                        pendingResumeRefresh = false
                        shouldRefreshContext = true
                    }
                } else {
                    resumeStableSinceMs = nowMs
                }
            }

            val skipSpeedForUi = isNewWidget ||
                hasContextPanel ||
                hasOtherModal ||
                addedLinesHasUiBanner ||
                pendingResumeRefresh ||
                nowMs <= suppressSpeedUntilMs

            if (compactPendingRefresh) {
                if (deltaChars >= MIN_DELTA_CHARS && !hasContextPanel) {
                    compactRefreshTriggerAtMs = maxOf(compactRefreshTriggerAtMs, nowMs + COMPACT_REFRESH_DELAY_MS)
                } else if (nowMs >= compactRefreshTriggerAtMs && isAtMainPrompt(lines)) {
                    compactPendingRefresh = false
                    shouldRefreshContext = true
                }
            } else if (!skipSpeedForUi) {
                if (deltaChars >= MIN_DELTA_CHARS) {
                    if (burstTicks == 0) {
                        burstStartMs = nowMs - POLL_INTERVAL_MS
                        accumulatedChars = 0L
                    }
                    burstTicks++
                    accumulatedChars += deltaChars
                    lastActiveMs = nowMs

                    // 至少连续 3 个有效增量周期（>= 1.5s）才确认为真正的流式输出，过滤启动横幅与历史回放
                    if (burstTicks >= MIN_BURST_TICKS) {
                        isStreaming = true
                        if (!exactTurnSpeedActive) {
                            val elapsedSec = maxOf(0.5, (nowMs - burstStartMs) / 1000.0)
                            val tokPerSec = (accumulatedChars / CHARS_PER_TOKEN) / elapsedSec
                            tokPerSecText = formatSpeed(tokPerSec)
                        }
                    }
                } else if (nowMs - lastActiveMs >= IDLE_TIMEOUT_MS) {
                    if (isStreaming) {
                        isStreaming = false
                        if (!exactTurnSpeedActive) {
                            val activeSec = maxOf(0.5, (lastActiveMs - burstStartMs) / 1000.0)
                            val avgTokPerSec = (accumulatedChars / CHARS_PER_TOKEN) / activeSec
                            tokPerSecText = formatSpeed(avgTokPerSec)
                        }
                        exactTurnSpeedActive = false
                    }
                    burstTicks = 0
                }
            } else if (isStreaming || burstTicks > 0) {
                resetPendingBurst()
            }

            newDisplayText = buildDisplayText()
            newTooltipText = buildTooltipText()
        }

        if (shouldSendEscape) {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    AgyTerminalService.getInstance(project).sendEscapeCommand()
                }
            }, ModalityState.any())
        }

        if (shouldRefreshContext) {
            triggerContextRefresh(autoDismiss = true)
        }

        val prevDisplay = displayTextRef.getAndSet(newDisplayText)
        val prevTooltip = tooltipTextRef.getAndSet(newTooltipText)
        if (prevDisplay != newDisplayText || prevTooltip != newTooltipText) {
            notifyStatusBarUpdate()
        }
    }

    /**
     * 清理尚未结束或被 UI 弹层/启动横幅打断的临时采样周期。
     */
    private fun resetPendingBurst() {
        isStreaming = false
        burstTicks = 0
        accumulatedChars = 0L
        exactTurnSpeedActive = false
    }

    /**
     * 基于 `agy` statusLine 提供的真实 output_tokens 与 total_api_duration_ms 计算精确输出速度；
     * 若账号/版本未提供 total_api_duration_ms，则在检测到真实流式回合结束时用真实 output_tokens 校准平均速度。
     */
    private fun applyExactOutputSpeedFromTelemetry(telemetry: StatusLineTelemetry, nowMs: Long) {
        val totalOut = telemetry.totalOutputTokens
        val totalApiMs = telemetry.totalApiDurationMs
        val prevOut = lastTotalOutputTokens
        val prevApiMs = lastTotalApiDurationMs

        if (totalOut != null) {
            lastTotalOutputTokens = totalOut
        }
        if (totalApiMs != null) {
            lastTotalApiDurationMs = totalApiMs
        }

        // 首次拿到遥测或发生 /resume 会话跳变（累计值大幅突变且远超单轮 output_tokens）时只建立基线
        if (prevOut == null || totalOut == null || totalOut < prevOut) {
            return
        }

        val deltaOut = totalOut - prevOut
        val singleTurnOut = telemetry.currentOutputTokens
        val looksLikeSessionResumeJump = singleTurnOut != null && deltaOut > singleTurnOut * 2 + 500
        if (looksLikeSessionResumeJump) {
            return
        }

        // 1. 优先使用真实的 API 增量耗时与增量 output_tokens
        if (totalApiMs != null && prevApiMs != null && totalApiMs > prevApiMs && deltaOut > 0L) {
            val deltaApiSec = (totalApiMs - prevApiMs) / 1000.0
            if (deltaApiSec >= 0.05) {
                tokPerSecText = formatSpeed(deltaOut / deltaApiSec)
                exactTurnSpeedActive = true
                return
            }
        }

        // 2. 若无 total_api_duration_ms，则用真实单轮 output_tokens 结合实测流式耗时校准
        val turnTokens = if (deltaOut > 0L) deltaOut else (singleTurnOut ?: 0L)
        if (turnTokens > 0L && (isStreaming || burstTicks > 0) && nowMs > burstStartMs) {
            val activeSec = maxOf(0.5, (nowMs - burstStartMs) / 1000.0)
            tokPerSecText = formatSpeed(turnTokens / activeSec)
            exactTurnSpeedActive = true
        }
    }

    /**
     * 在切换会话（如 /resume）时清空已过期的旧上下文百分比缓存。
     */
    private fun clearContextMetrics() {
        contextPercentValue = null
        contextPercentText = null
        contextDetailText = null
        cachedPercentText = null
    }

    /**
     * 读取终端缓冲区中最近 30 行历史记录与当前可视屏幕行，并裁掉每行末尾空白。
     */
    private fun readRecentTerminalLines(widget: ShellTerminalWidget): List<String>? {
        val buffer = try {
            widget.terminalTextBuffer
        } catch (_: Exception) {
            return null
        }

        val result = mutableListOf<String>()
        buffer.lock()
        try {
            val historyStorage = buffer.historyLinesStorage
            val historySize = historyStorage.size
            val historyStart = maxOf(0, historySize - HISTORY_LINES_TO_READ)
            for (i in historyStart until historySize) {
                result.add(historyStorage.get(i).text.trimEnd())
            }

            val screenStorage = buffer.screenLinesStorage
            val screenSize = screenStorage.size
            for (i in 0 until screenSize) {
                result.add(screenStorage.get(i).text.trimEnd())
            }
        } finally {
            buffer.unlock()
        }

        while (result.isNotEmpty() && result.last().isEmpty()) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    /**
     * 拼装状态栏展示文本，按当前 IDE 语言展示上下文占比（含阈值 ⚠️ 预警）、缓存命中率与输出速度（含流式 ⚡ 标识）。
     */
    private fun buildDisplayText(): String {
        val streamPrefix = if (isStreaming) "⚡ " else ""
        val warnPrefix = if ((contextPercentValue ?: 0.0) >= CONTEXT_WARN_THRESHOLD) "⚠️ " else ""
        val ctxPart = contextPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val cachePart = cachedPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val speedPart = tokPerSecText ?: DEFAULT_SPEED_PLACEHOLDER
        return AgyBundle.message("statusbar.metrics.label", streamPrefix, warnPrefix, ctxPart, cachePart, speedPart)
    }

    /**
     * 拼装鼠标悬停提示文本，展示完整 Token 比例、缓存命中率、实时/平均速度及左右键交互说明。
     */
    private fun buildTooltipText(): String {
        val ctxDetail = contextDetailText ?: (contextPercentText ?: DEFAULT_PERCENT_PLACEHOLDER)
        val cachePart = cachedPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val speedPart = tokPerSecText ?: DEFAULT_SPEED_PLACEHOLDER
        return AgyBundle.message("statusbar.metrics.tooltipDetail", ctxDetail, cachePart, speedPart)
    }

    /**
     * 通知所有已挂载的状态栏监控控件在 EDT 上同步最新文案并重绘。
     */
    private fun notifyStatusBarUpdate() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            listeners.forEach { it() }
            WindowManager.getInstance()
                .getStatusBar(project)
                ?.updateWidget(AgyStatusBarWidget.WIDGET_ID)
        }, ModalityState.any())
    }

    /**
     * 读取 `agy` 通过 statusLine 写入临时文件的最新 JSON 遥测数据，提取上下文占用率与缓存命中率。
     */
    private fun readStatusLineTelemetryIfUpdated(): StatusLineTelemetry? {
        if (!metricsOutputFile.isFile) return null
        val mtime = metricsOutputFile.lastModified()
        if (mtime <= 0L || mtime == lastMetricsFileMtime) return null

        return try {
            val content = Files.readString(metricsOutputFile.toPath(), StandardCharsets.UTF_8)
            if (content.isBlank()) return null
            val parsed = parseStatusLineJson(content) ?: return null
            lastMetricsFileMtime = mtime
            parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析后的 `agy` statusLine 结构化遥测指标。
     */
    internal data class StatusLineTelemetry(
        val contextUsage: ContextUsageInfo?,
        val cacheHitPercentText: String?,
        val totalOutputTokens: Long?,
        val currentOutputTokens: Long?,
        val totalApiDurationMs: Long?
    )

    /**
     * 解析出的上下文占用百分比及详情文本。
     */
    internal data class ContextUsageInfo(
        val percent: Double,
        val percentText: String,
        val detailText: String
    )

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val IDLE_TIMEOUT_MS = 1500L
        private const val STARTUP_SPEED_SUPPRESS_MS = 5000L
        private const val CONTEXT_DISMISS_TIMEOUT_MS = 2500L
        private const val COMMAND_SPEED_SUPPRESS_MS = 2000L
        private const val DISMISS_SPEED_SUPPRESS_MS = 1000L
        private const val COMPACT_SPEED_SUPPRESS_MS = 3500L
        private const val COMPACT_REFRESH_DELAY_MS = 2000L
        private const val RESUME_REFRESH_DELAY_MS = 500L
        private const val HISTORY_LINES_TO_READ = 30
        private const val MIN_DELTA_CHARS = 4
        private const val MIN_BURST_TICKS = 3
        private const val SUDDEN_HISTORY_JUMP_CHARS = 120
        private const val CHARS_PER_TOKEN = 3.6
        private const val CONTEXT_WARN_THRESHOLD = 80.0
        private const val DEFAULT_PERCENT_PLACEHOLDER = "--%"
        private const val DEFAULT_SPEED_PLACEHOLDER = "-- tok/s"

        /**
         * 返回跟随当前 IDE 语言环境的默认状态栏占位文本。
         */
        fun defaultDisplayText(): String = AgyBundle.message(
            "statusbar.metrics.label",
            "",
            "",
            DEFAULT_PERCENT_PLACEHOLDER,
            DEFAULT_PERCENT_PLACEHOLDER,
            DEFAULT_SPEED_PLACEHOLDER
        )

        private val CONTEXT_TOKENS_PATTERN = Regex(
            """(?i)(\d+(?:\.\d+)?[kKmM]?)\s*/\s*(\d+(?:\.\d+)?[kKmM]?)\s*tokens\s*\(\s*(\d+(?:\.\d+)?)\s*%\s*\)"""
        )
        private val FREE_SPACE_PATTERN = Regex(
            """(?i)free\s+space\s*:\s*([^(]*?)\(\s*(\d+(?:\.\d+)?)\s*%\s*\)"""
        )
        private val PERCENT_PATTERN = Regex(
            """(?i)(?:cache(?:\s+hit)?|cached)\s*[:=]?\s*(\d+(?:\.\d+)?)\s*%"""
        )
        private val RATIO_PATTERN = Regex(
            """(?i)(?:cache(?:\s+hit)?|cached)\s*[:=]?\s*(\d+(?:\.\d+)?)\s*([kKmM]?)\s*/\s*(\d+(?:\.\d+)?)\s*([kKmM]?)"""
        )
        private val TUI_DECORATION_PATTERN = Regex("""[□■▪●·│─┌┐└┘├┤┬┴┼\s]+""")
        private val PANEL_MARKERS = listOf(
            "Context Usage",
            "Free space:",
            "Auto-compact at server default",
            "Estimated usage (",
            "Related: /artifact",
            "esc Close"
        )
        private val NON_STREAM_UI_MARKERS = listOf(
            "Gemini 4 Carbon now available",
            "Both Carbon and Argon are now included",
            "esc to dismiss",
            "Auto mode: edits & safe commands",
            "? for shortcuts",
            "Conversation already open",
            "When you opened this conversation it was already open"
        )

        /**
         * 获取项目级的状态栏指标服务实例。
         */
        fun getInstance(project: Project): AgyMetricsService =
            project.getService(AgyMetricsService::class.java)

        /**
         * 解析 `agy` statusLine 传入的 JSON 数据，提取上下文占比、缓存命中率、真实输出 token 与 API 耗时。
         */
        internal fun parseStatusLineJson(jsonText: String): StatusLineTelemetry? {
            val root = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
            val ctxObj = root.getAsJsonObject("context_window")
            val costObj = root.getAsJsonObject("cost")

            val windowSize = ctxObj?.getLongOrNull("context_window_size") ?: 0L
            val usedPctRaw = ctxObj?.getDoubleOrNull("used_percentage")?.coerceIn(0.0, 100.0)
            val contextUsage = usedPctRaw?.let { pct ->
                val pctText = formatOneDecimalPercent(pct)
                val detail = if (windowSize > 0L) {
                    val usedTokens = (windowSize * (pct / 100.0)).roundToLong()
                    "${formatTokenCount(usedTokens)}/${formatTokenCount(windowSize)} tokens ($pctText)"
                } else {
                    pctText
                }
                ContextUsageInfo(pct, pctText, detail)
            }

            val currentUsage = ctxObj?.getAsJsonObject("current_usage")
            val cacheHitText = currentUsage?.let { usage ->
                val uncachedInput = usage.getLongOrNull("input_tokens") ?: 0L
                val cacheCreation = usage.getLongOrNull("cache_creation_input_tokens") ?: 0L
                val cacheRead = usage.getLongOrNull("cache_read_input_tokens")
                    ?: usage.getLongOrNull("cached_tokens")
                    ?: usage.getLongOrNull("cachedContentTokenCount")
                    ?: 0L
                val totalInput = uncachedInput + cacheCreation + cacheRead
                if (totalInput > 0L) {
                    val hitPct = (cacheRead.toDouble() / totalInput.toDouble() * 100.0).coerceIn(0.0, 100.0)
                    formatCompactPercent(hitPct)
                } else {
                    null
                }
            }

            val totalOutputTokens = ctxObj?.getLongOrNull("total_output_tokens")
                ?: root.getLongOrNull("output_tokens")
            val currentOutputTokens = currentUsage?.getLongOrNull("output_tokens")
            val totalApiDurationMs = costObj?.getLongOrNull("total_api_duration_ms")
                ?: root.getLongOrNull("total_api_duration_ms")

            if (contextUsage == null && cacheHitText == null && totalOutputTokens == null && totalApiDurationMs == null) {
                return null
            }
            return StatusLineTelemetry(
                contextUsage = contextUsage,
                cacheHitPercentText = cacheHitText,
                totalOutputTokens = totalOutputTokens,
                currentOutputTokens = currentOutputTokens,
                totalApiDurationMs = totalApiDurationMs
            )
        }

        /**
         * 从 JsonObject 安全读取浮点数值，字段缺失或非数字时返回 null。
         */
        private fun JsonObject.getDoubleOrNull(key: String): Double? {
            val el = get(key) ?: return null
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asDouble else null
        }

        /**
         * 从 JsonObject 安全读取长整型数值，字段缺失或非数字时返回 null。
         */
        private fun JsonObject.getLongOrNull(key: String): Long? {
            val el = get(key) ?: return null
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asLong else null
        }

        /**
         * 将 token 数量格式化为紧凑字符串（如 `1.0M`、`12.4k`）。
         */
        private fun formatTokenCount(tokens: Long): String = when {
            tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }

        /**
         * 判断当前缓冲区行中是否包含 `/context` 面板的特征文本，用于屏蔽面板重绘与限定解析范围。
         */
        internal fun containsContextPanel(lines: List<String>): Boolean =
            lines.any { line -> PANEL_MARKERS.any { marker -> line.contains(marker) } }

        /**
         * 判断当前终端是否启用了备用全屏缓冲区（部分交互选择面板会切换至此模式）。
         */
        private fun isAlternateBufferActive(widget: ShellTerminalWidget): Boolean = try {
            widget.terminalTextBuffer.isUsingAlternateBuffer
        } catch (_: Exception) {
            false
        }

        /**
         * 提取底部输入提示栏之前的对话正文行，用于比对会话是否被整体替换。
         */
        private fun extractConversationBody(lines: List<String>): List<String> {
            val promptHintIdx = lines.indexOfLast { it.contains("? for shortcuts") }
            val cutoff = if (promptHintIdx >= 0) maxOf(0, promptHintIdx - 3) else lines.size
            return lines.subList(0, cutoff)
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() && NON_STREAM_UI_MARKERS.none { marker -> line.contains(marker) }
                }
        }

        /**
         * 判断主界面正文的非空行是否发生了整体替换（例如通过 /resume 切换历史或 /clear 清空会话）。
         */
        private fun hasBodyReplaced(
            prevBody: List<String>,
            currBody: List<String>,
            fromModal: Boolean = false
        ): Boolean {
            if (prevBody == currBody) return false
            if (prevBody.isEmpty()) {
                return if (fromModal) currBody.isNotEmpty() else currBody.size >= 5
            }
            if (currBody.isEmpty()) return prevBody.size >= 2
            val prevSet = prevBody.toHashSet()
            val overlap = currBody.count { prevSet.contains(it) }
            return overlap * 3 < currBody.size
        }

        /**
         * 判断当前缓冲区是否打开了交互式弹层（如 `/resume` 会话选择列表等）。
         */
        internal fun hasModalOverlay(lines: List<String>): Boolean {
            val tail = lines.takeLast(10)
            return tail.any {
                it.contains("esc Close", ignoreCase = true) ||
                    it.contains("esc Cancel", ignoreCase = true) ||
                    it.contains("esc to cancel", ignoreCase = true) ||
                    it.contains("↑/↓", ignoreCase = true) ||
                    it.contains("↑↓", ignoreCase = true) ||
                    it.contains("pgup/pgdown", ignoreCase = true) ||
                    it.contains("enter to select", ignoreCase = true) ||
                    it.contains("resume", ignoreCase = true) && it.contains("esc", ignoreCase = true)
            }
        }

        /**
         * 判断当前是否位于主输入界面（底部存在 `? for shortcuts` 且未打开 `esc Close` / `↑/↓ Scroll` 弹层）。
         */
        internal fun isAtMainPrompt(lines: List<String>): Boolean {
            val tail = lines.takeLast(8)
            val hasPromptHint = tail.any { it.contains("? for shortcuts") }
            val hasModal = tail.any { it.contains("esc Close") || it.contains("↑/↓ Scroll") }
            return hasPromptHint && !hasModal
        }

        /**
         * 检查本次刷新新增或变化的行中是否包含 CLI 横幅/系统提示等非正文 UI。
         */
        internal fun hasNonStreamUiBannerInDelta(prevLines: List<String>, currLines: List<String>): Boolean {
            val prevSet = prevLines.toHashSet()
            return currLines.any { line ->
                !prevSet.contains(line) && NON_STREAM_UI_MARKERS.any { marker -> line.contains(marker) }
            }
        }

        /**
         * 提取用于识别会话恢复（/resume 或多实例冲突提示）的特征签名。
         */
        private fun computeResumeSignature(lines: List<String>): Int {
            val markerLine = lines.lastOrNull { line ->
                line.contains("Conversation already open") ||
                    line.trim() == "/resume" ||
                    line.contains("> /resume")
            } ?: return 0
            val idx = lines.lastIndexOf(markerLine)
            val contextSlice = lines.subList(maxOf(0, idx - 1), idx + 1)
            return contextSlice.hashCode()
        }

        /**
         * 仅在 `Context Usage` 面板区间内提取最近一次出现的上下文占用率信息，避免误匹配对话正文中的数字。
         */
        internal fun extractLatestContextUsage(lines: List<String>): ContextUsageInfo? {
            val panelStartIdx = lines.indexOfLast { it.contains("Context Usage") }
            val searchStart = if (panelStartIdx >= 0) panelStartIdx else maxOf(0, lines.size - 16)
            val panelLines = lines.subList(searchStart, lines.size)

            for (i in panelLines.indices.reversed()) {
                val line = panelLines[i]
                if (line.isBlank()) continue

                val tokenMatch = CONTEXT_TOKENS_PATTERN.findAll(line).lastOrNull()
                if (tokenMatch != null) {
                    val pct = tokenMatch.groupValues[3].toDoubleOrNull()?.coerceIn(0.0, 100.0)
                    if (pct != null) {
                        val pctText = formatOneDecimalPercent(pct)
                        val detail = "${tokenMatch.groupValues[1]}/${tokenMatch.groupValues[2]} tokens ($pctText)"
                        return ContextUsageInfo(pct, pctText, detail)
                    }
                }
            }

            for (i in panelLines.indices.reversed()) {
                val line = panelLines[i]
                if (line.isBlank()) continue

                val freeMatch = FREE_SPACE_PATTERN.findAll(line).lastOrNull()
                if (freeMatch != null) {
                    val freePct = freeMatch.groupValues[2].toDoubleOrNull()?.coerceIn(0.0, 100.0)
                    if (freePct != null) {
                        val usedPct = (100.0 - freePct).coerceIn(0.0, 100.0)
                        val pctText = formatOneDecimalPercent(usedPct)
                        val detail = "$pctText (Free: ${freeMatch.groupValues[1].trim()})"
                        return ContextUsageInfo(usedPct, pctText, detail)
                    }
                }
            }

            return null
        }

        /**
         * 从缓冲区行列表中自下而上提取最近一次出现的缓存命中率百分比。
         * 兼容 `Cache hit: 84.2%`、`Cached: 84%` 及 `Cached: 12.4k / 15.0k` 等格式。
         */
        internal fun extractLatestCachePercent(lines: List<String>): String? {
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                if (line.isBlank()) continue

                val percentMatch = PERCENT_PATTERN.findAll(line).lastOrNull()
                val ratioMatch = RATIO_PATTERN.findAll(line).lastOrNull()

                val chosenPercent: Double? = when {
                    percentMatch != null && ratioMatch != null -> {
                        if (percentMatch.range.first >= ratioMatch.range.first) {
                            percentMatch.groupValues[1].toDoubleOrNull()
                        } else {
                            computeRatioPercent(ratioMatch)
                        }
                    }
                    percentMatch != null -> percentMatch.groupValues[1].toDoubleOrNull()
                    ratioMatch != null -> computeRatioPercent(ratioMatch)
                    else -> null
                }

                if (chosenPercent != null && chosenPercent in 0.0..100.0) {
                    return formatCompactPercent(chosenPercent)
                }
            }
            return null
        }

        /**
         * 计算两次缓冲区快照之间真正新增的有效正文字符数，过滤 TUI 框线与进度方块符号。
         */
        internal fun computeAddedVisibleChars(prevLines: List<String>, currLines: List<String>): Int {
            if (currLines.isEmpty() || prevLines == currLines) return 0
            if (prevLines.isEmpty()) {
                return currLines.sumOf { countContentChars(it) }
            }

            val stablePrefixCount = prevLines.size - 1
            if (stablePrefixCount >= 1) {
                for (shift in 0 until prevLines.size) {
                    val overlapLen = stablePrefixCount - shift
                    if (overlapLen <= 0 || overlapLen > currLines.size) continue

                    var allMatch = true
                    for (j in 0 until overlapLen) {
                        if (prevLines[shift + j] != currLines[j]) {
                            allMatch = false
                            break
                        }
                    }
                    if (allMatch) {
                        var added = diffAddedContentCharsOnLine(prevLines.last(), currLines.getOrElse(overlapLen) { "" })
                        for (k in (overlapLen + 1) until currLines.size) {
                            added += countContentChars(currLines[k])
                        }
                        return added
                    }
                }
            }

            val prevTotal = prevLines.sumOf { countContentChars(it) }
            val currTotal = currLines.sumOf { countContentChars(it) }
            if (currTotal * 2 < prevTotal) return 0

            var diffSum = 0
            val minSize = minOf(prevLines.size, currLines.size)
            for (i in 0 until minSize) {
                diffSum += diffAddedContentCharsOnLine(prevLines[i], currLines[i])
            }
            for (i in minSize until currLines.size) {
                diffSum += countContentChars(currLines[i])
            }
            return diffSum
        }

        /**
         * 将比例形式（如 `12.4k / 15.0k`）换算为百分比数值。
         */
        private fun computeRatioPercent(match: MatchResult): Double? {
            val num = match.groupValues[1].toDoubleOrNull() ?: return null
            val numMul = unitMultiplier(match.groupValues[2])
            val den = match.groupValues[3].toDoubleOrNull() ?: return null
            val denMul = unitMultiplier(match.groupValues[4])
            val totalDen = den * denMul
            if (totalDen <= 0.0) return null
            return ((num * numMul) / totalDen * 100.0).coerceIn(0.0, 100.0)
        }

        /**
         * 解析数量单位后缀（k/M）的乘数。
         */
        private fun unitMultiplier(unit: String): Double = when (unit.lowercase(Locale.ROOT)) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }

        /**
         * 剔除单行文本的公共前后缀后，统计新文本中真正新增的有效正文字符数。
         */
        private fun diffAddedContentCharsOnLine(prev: String, curr: String): Int {
            if (prev == curr) return 0
            var prefix = 0
            val minLen = minOf(prev.length, curr.length)
            while (prefix < minLen && prev[prefix] == curr[prefix]) {
                prefix++
            }
            var suffix = 0
            while (suffix < minLen - prefix &&
                prev[prev.length - 1 - suffix] == curr[curr.length - 1 - suffix]
            ) {
                suffix++
            }
            val changedSlice = curr.substring(prefix, curr.length - suffix)
            return countContentChars(changedSlice)
        }

        /**
         * 剔除 TUI 方块、圆点、框线及空白字符后统计有效正文字符数。
         */
        private fun countContentChars(text: String): Int =
            text.replace(TUI_DECORATION_PATTERN, "").length

        /**
         * 格式化上下文占用率百分比，固定保留一位小数（如 `0.0%`）。
         */
        private fun formatOneDecimalPercent(value: Double): String =
            String.format(Locale.US, "%.1f%%", value)

        /**
         * 格式化缓存命中率百分比，最多保留一位小数。
         */
        private fun formatCompactPercent(value: Double): String {
            val df = DecimalFormat("0.#", DecimalFormatSymbols(Locale.US))
            return "${df.format(value)}%"
        }

        /**
         * 格式化 tok/s 输出速度，保留一位小数。
         */
        private fun formatSpeed(tokPerSec: Double): String =
            String.format(Locale.US, "%.1f tok/s", tokPerSec)
    }
}
