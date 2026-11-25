package com.github.agylauncher.terminal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.github.agylauncher.AgyBundle
import com.github.agylauncher.settings.AgySettingsState
import com.github.agylauncher.statusbar.AgyStatusBarWidget
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
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
    /** 第二行 5h 剩余额度默认展示文案 */
    private val quotaTextRef = AtomicReference(defaultQuotaText())
    private val tooltipTextRef = AtomicReference(AgyBundle.message("statusbar.metrics.tooltipDefault"))
    private val pollingTaskRef = AtomicReference<ScheduledFuture<*>?>(null)
    private val started = AtomicBoolean(false)
    /** 标记是否已发起后台拉取 agy 可用第三方模型列表的任务 */
    private val thirdPartyFetchStarted = AtomicBoolean(false)
    /** 从 `agy models` 动态解析出的第三方模型族展示名称（如 `CLAUDE&GPT`） */
    private val thirdPartyModelLabelRef = AtomicReference<String?>(null)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val stateLock = Any()
    private var previousLines: List<String>? = null
    private var contextPercentValue: Double? = null
    private var contextPercentText: String? = null
    private var contextDetailText: String? = null
    private var cachedPercentText: String? = null
    private var tokPerSecText: String? = null
    /** 5 小时周期剩余额度百分比数值，用于低额度预警判断 */
    private var fiveHourQuotaPercentValue: Double? = null
    /** 5 小时周期剩余额度格式化文案 */
    private var fiveHourQuotaPercentText: String? = null
    /** 周周期剩余额度百分比数值，用于低额度预警判断 */
    private var weeklyQuotaPercentValue: Double? = null
    /** 周周期剩余额度格式化文案 */
    private var weeklyQuotaPercentText: String? = null
    /** 全部配额池悬停展示的 HTML 明细 */
    private var quotaDetailText: String? = null
    /** 最近一次解析到的原始配额桶列表，用于在第三方模型名异步加载完成后刷新浮窗文案 */
    private var lastQuotaBuckets: List<QuotaBucketEntry> = emptyList()
    /** 最近一次解析额度时的活跃模型标识 */
    private var lastQuotaModelHint: String? = null
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
    /** 上次成功解析的 statusLine 文件签名，避免同秒覆写被误判为未变更 */
    private var lastMetricsFileSignature: MetricsFileSignature? = null
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
        ensureThirdPartyModelsLoadedAsync()
        val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { pollSafely() },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        pollingTaskRef.set(future)
    }

    /**
     * 获取当前应在状态栏第一行展示的上下文、命中缓存与输出速度文本。
     */
    fun getDisplayLabel(): String = displayTextRef.get()

    /**
     * 获取当前应在状态栏第二行展示的默认 5h 剩余额度文本。
     */
    fun getQuotaLabel(): String = quotaTextRef.get()

    /**
     * 获取鼠标悬停时的详细指标、全部额度及操作提示文本。
     */
    fun getTooltipText(): String = tooltipTextRef.get()

    /**
     * 生成会话级临时 settings.json，配置 `agy` 的 `statusLine` 将官方上下文与缓存 JSON 实时写入临时指标文件。
     */
    fun prepareSessionSettingsFile(): String? {
        return try {
            metricsOutputFile.delete()
            lastMetricsFileSignature = null

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
        lastMetricsFileSignature = null
        synchronized(stateLock) {
            lastTotalOutputTokens = null
            lastTotalApiDurationMs = null
            exactTurnSpeedActive = false
        }
        if (thirdPartyModelLabelRef.get() == null) {
            thirdPartyFetchStarted.set(false)
            ensureThirdPartyModelsLoadedAsync()
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
     * 在后台线程异步执行 `agy models`，动态提取第三方模型族名称并在完成后刷新浮窗明细。
     */
    private fun ensureThirdPartyModelsLoadedAsync() {
        if (!thirdPartyFetchStarted.compareAndSet(false, true)) return
        AppExecutorUtil.getAppScheduledExecutorService().execute {
            if (project.isDisposed) return@execute
            val dynamicLabel = fetchThirdPartyModelsLabel() ?: return@execute
            thirdPartyModelLabelRef.set(dynamicLabel)
            val updatedTooltip: String?
            synchronized(stateLock) {
                if (lastQuotaBuckets.isNotEmpty()) {
                    quotaDetailText = formatAllQuotasHtml(lastQuotaBuckets, lastQuotaModelHint, dynamicLabel)
                    updatedTooltip = buildTooltipText()
                } else {
                    updatedTooltip = null
                }
            }
            if (updatedTooltip != null) {
                tooltipTextRef.set(updatedTooltip)
                notifyStatusBarUpdate()
            }
        }
    }

    /**
     * 返回当前可用的第三方模型展示名；尚未拉取完成或失败时回退为多语言兜底标签（如 `3P` / `第三方`）。
     */
    private fun resolveThirdPartyLabel(): String =
        thirdPartyModelLabelRef.get() ?: AgyBundle.message("statusbar.metrics.thirdPartyFallback")

    /**
     * 执行 `agy models` 命令并提取所有非 Gemini 模型的厂商家族前缀（如 `CLAUDE&GPT`）。
     */
    private fun fetchThirdPartyModelsLabel(): String? {
        val rawExe = AgySettingsState.getInstance().commandPath.trim().ifEmpty { "agy" }
        val homeDir = System.getProperty("user.home").orEmpty()
        val expandedExe = if (rawExe.startsWith("~/") && homeDir.isNotEmpty()) {
            homeDir + rawExe.substring(1)
        } else {
            rawExe
        }
        val candidates = mutableListOf(expandedExe)
        if (expandedExe == "agy" && !SystemInfo.isWindows && homeDir.isNotEmpty()) {
            val localAgy = File(homeDir, ".local/bin/agy")
            if (localAgy.isFile && localAgy.canExecute()) {
                candidates.add(localAgy.absolutePath)
            }
        }

        for (exePath in candidates) {
            val stdout = runCatching {
                val cmdLine = GeneralCommandLine(exePath, "models")
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                project.basePath?.let { cmdLine.withWorkDirectory(it) }
                val output = CapturingProcessHandler(cmdLine).runProcess(MODELS_FETCH_TIMEOUT_MS)
                if (output.exitCode == 0) output.stdout else null
            }.getOrNull() ?: continue

            val parsed = parseThirdPartyModelsLabel(stdout)
            if (!parsed.isNullOrBlank()) {
                return parsed
            }
        }
        return null
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
        val newQuotaText: String
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
                statusLineTelemetry.quotaSummary?.let { quota ->
                    fiveHourQuotaPercentValue = quota.fiveHourPercent
                    fiveHourQuotaPercentText = quota.fiveHourPercentText
                    weeklyQuotaPercentValue = quota.weeklyPercent
                    weeklyQuotaPercentText = quota.weeklyPercentText
                    quotaDetailText = quota.detailText
                    lastQuotaBuckets = quota.buckets
                    lastQuotaModelHint = quota.activeModelHint
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
            newQuotaText = buildQuotaLineText()
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
        val prevQuota = quotaTextRef.getAndSet(newQuotaText)
        val prevTooltip = tooltipTextRef.getAndSet(newTooltipText)
        if (prevDisplay != newDisplayText || prevQuota != newQuotaText || prevTooltip != newTooltipText) {
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
     * 拼装状态栏第一行展示文本：按当前 IDE 语言展示上下文占比（含阈值 ⚠️ 预警）、缓存命中率与输出速度（含流式 ⚡ 标识）。
     */
    private fun buildDisplayText(): String {
        val streamPrefix = if (isStreaming) "⚡ " else ""
        val ctxWarnPrefix = if ((contextPercentValue ?: 0.0) >= CONTEXT_WARN_THRESHOLD) "⚠️ " else ""
        val ctxPart = contextPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val cachePart = cachedPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val speedPart = tokPerSecText ?: DEFAULT_SPEED_PLACEHOLDER
        return AgyBundle.message(
            "statusbar.metrics.label",
            streamPrefix,
            ctxWarnPrefix,
            ctxPart,
            cachePart,
            speedPart
        )
    }

    /**
     * 拼装状态栏第二行展示文本：默认并列展示当前模型对应的 5h 剩余额度与周剩余额度（含低额度预警标识）。
     */
    private fun buildQuotaLineText(): String {
        val fiveHourWarnPrefix = if ((fiveHourQuotaPercentValue ?: 100.0) <= QUOTA_LOW_WARN_THRESHOLD) "⚠️ " else ""
        val fiveHourPart = fiveHourQuotaPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        val weeklyWarnPrefix = if ((weeklyQuotaPercentValue ?: 100.0) <= QUOTA_LOW_WARN_THRESHOLD) "⚠️ " else ""
        val weeklyPart = weeklyQuotaPercentText ?: DEFAULT_PERCENT_PLACEHOLDER
        return AgyBundle.message(
            "statusbar.metrics.quotaLabel",
            fiveHourWarnPrefix,
            fiveHourPart,
            weeklyWarnPrefix,
            weeklyPart
        )
    }

    /**
     * 拼装鼠标悬停 HTML 提示文本，展示完整 Token 比例、缓存命中率、实时/平均速度、全部配额池明细及左右键交互说明。
     */
    private fun buildTooltipText(): String {
        val ctxDetail = StringUtil.escapeXmlEntities(contextDetailText ?: (contextPercentText ?: DEFAULT_PERCENT_PLACEHOLDER))
        val cachePart = StringUtil.escapeXmlEntities(cachedPercentText ?: DEFAULT_PERCENT_PLACEHOLDER)
        val speedPart = StringUtil.escapeXmlEntities(tokPerSecText ?: DEFAULT_SPEED_PLACEHOLDER)
        val quotaHtml = quotaDetailText ?: AgyBundle.message("statusbar.metrics.quotaWaiting")
        return AgyBundle.message("statusbar.metrics.tooltipDetail", ctxDetail, cachePart, speedPart, quotaHtml)
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
     * 读取 `agy` 通过 statusLine 写入临时文件的最新 JSON 遥测数据；
     * 使用高精度修改时间、文件长度及 inode 签名判断变更，避免同秒内多次覆写（如启动后异步补齐 quota）被跳过。
     */
    private fun readStatusLineTelemetryIfUpdated(): StatusLineTelemetry? {
        if (!metricsOutputFile.isFile) return null
        val path = metricsOutputFile.toPath()
        val signature = try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            MetricsFileSignature(attrs.lastModifiedTime().toInstant(), attrs.size(), attrs.fileKey())
        } catch (_: Exception) {
            return null
        }
        if (signature == lastMetricsFileSignature) return null

        return try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            if (content.isBlank()) return null
            val parsed = parseStatusLineJson(content, resolveThirdPartyLabel()) ?: return null
            lastMetricsFileSignature = signature
            parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 遥测文件高精度变更签名，防止同秒写入导致漏读 quota。
     */
    private data class MetricsFileSignature(
        val modifiedAt: Instant,
        val size: Long,
        val fileKey: Any?
    )

    /**
     * 解析后的 `agy` statusLine 结构化遥测指标。
     */
    internal data class StatusLineTelemetry(
        val contextUsage: ContextUsageInfo?,
        val cacheHitPercentText: String?,
        val quotaSummary: QuotaSummary?,
        val totalOutputTokens: Long?,
        val currentOutputTokens: Long?,
        val totalApiDurationMs: Long?
    )

    /**
     * 解析出的额度汇总信息（包含当前模型 5h/周剩余额度主展示、悬停全部配额池明细及原始桶列表）。
     */
    internal data class QuotaSummary(
        val fiveHourPercent: Double?,
        val fiveHourPercentText: String?,
        val weeklyPercent: Double?,
        val weeklyPercentText: String?,
        val detailText: String,
        val buckets: List<QuotaBucketEntry>,
        val activeModelHint: String?
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
        private const val QUOTA_LOW_WARN_THRESHOLD = 20.0
        private const val MODELS_FETCH_TIMEOUT_MS = 25_000
        private const val DEFAULT_PERCENT_PLACEHOLDER = "--%"
        private const val DEFAULT_SPEED_PLACEHOLDER = "-- tok/s"

        /**
         * 返回跟随当前 IDE 语言环境的状态栏第一行默认占位文本。
         */
        fun defaultDisplayText(): String = AgyBundle.message(
            "statusbar.metrics.label",
            "",
            "",
            DEFAULT_PERCENT_PLACEHOLDER,
            DEFAULT_PERCENT_PLACEHOLDER,
            DEFAULT_SPEED_PLACEHOLDER
        )

        /**
         * 返回跟随当前 IDE 语言环境的状态栏第二行 5h 与周额度默认占位文本。
         */
        fun defaultQuotaText(): String = AgyBundle.message(
            "statusbar.metrics.quotaLabel",
            "",
            DEFAULT_PERCENT_PLACEHOLDER,
            "",
            DEFAULT_PERCENT_PLACEHOLDER
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
        internal fun parseStatusLineJson(
            jsonText: String,
            thirdPartyLabel: String = AgyBundle.message("statusbar.metrics.thirdPartyFallback")
        ): StatusLineTelemetry? {
            val root = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
            val ctxObj = root.getObjectOrNull("context_window")
            val costObj = root.getObjectOrNull("cost")

            val windowSize = ctxObj?.getLongOrNull("context_window_size") ?: 0L
            val usedPctRaw = ctxObj?.getDoubleOrNull("used_percentage")?.coerceIn(0.0, 100.0)
            val contextUsage = usedPctRaw?.let { pct ->
                val pctText = formatOneDecimalPercent(pct)
                val detail = if (windowSize > 0L) {
                    val usedTokens = (windowSize * (pct / 100.0)).roundToLong()
                    "${formatTokenCount(usedTokens)}/${formatTokenCount(windowSize)} ($pctText)"
                } else {
                    pctText
                }
                ContextUsageInfo(pct, pctText, detail)
            }

            val currentUsage = ctxObj?.getObjectOrNull("current_usage")
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

            val modelObj = root.getObjectOrNull("model")
            val activeModelHint = (modelObj?.getStringOrNull("id") ?: modelObj?.getStringOrNull("display_name"))
                ?.lowercase(Locale.ROOT)
            val quotaSummary = parseQuotaSummary(root.getObjectOrNull("quota"), activeModelHint, thirdPartyLabel)

            val totalOutputTokens = ctxObj?.getLongOrNull("total_output_tokens")
                ?: root.getLongOrNull("output_tokens")
            val currentOutputTokens = currentUsage?.getLongOrNull("output_tokens")
            val totalApiDurationMs = costObj?.getLongOrNull("total_api_duration_ms")
                ?: root.getLongOrNull("total_api_duration_ms")

            if (contextUsage == null &&
                cacheHitText == null &&
                quotaSummary == null &&
                totalOutputTokens == null &&
                totalApiDurationMs == null
            ) {
                return null
            }
            return StatusLineTelemetry(
                contextUsage = contextUsage,
                cacheHitPercentText = cacheHitText,
                quotaSummary = quotaSummary,
                totalOutputTokens = totalOutputTokens,
                currentOutputTokens = currentOutputTokens,
                totalApiDurationMs = totalApiDurationMs
            )
        }

        /**
         * 从 `quota` JSON 对象中解析全部配额池剩余比例与刷新倒计时；
         * 状态栏第二行分别提取当前模型对应的 5h 额度与周额度，悬停提示按有序 HTML 表格展示全部配额池。
         */
        private fun parseQuotaSummary(
            quotaObj: JsonObject?,
            activeModelHint: String?,
            thirdPartyLabel: String
        ): QuotaSummary? {
            if (quotaObj == null || quotaObj.size() == 0) return null

            val buckets = mutableListOf<QuotaBucketEntry>()
            for ((bucketName, element) in quotaObj.entrySet()) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                if (obj.getBooleanOrNull("disabled") == true) continue
                val fraction = obj.getDoubleOrNull("remaining_fraction") ?: continue
                val pct = (fraction * 100.0).coerceIn(0.0, 100.0)
                val resetSeconds = resolveResetSeconds(obj)
                val resetLabel = resetSeconds?.let { formatResetCountdown(it) }
                buckets.add(QuotaBucketEntry(bucketName, pct, resetLabel))
            }

            if (buckets.isEmpty()) return null

            val fiveHourBucket = selectPrimaryPeriodBucket(buckets, activeModelHint) { isFiveHourBucket(it.name) }
            val weeklyBucket = selectPrimaryPeriodBucket(buckets, activeModelHint) { isWeeklyBucket(it.name) }
            val detailHtml = formatAllQuotasHtml(buckets, activeModelHint, thirdPartyLabel)
            return QuotaSummary(
                fiveHourPercent = fiveHourBucket?.remainingPercent,
                fiveHourPercentText = fiveHourBucket?.let { formatCompactPercent(it.remainingPercent) },
                weeklyPercent = weeklyBucket?.remainingPercent,
                weeklyPercentText = weeklyBucket?.let { formatCompactPercent(it.remainingPercent) },
                detailText = detailHtml,
                buckets = buckets,
                activeModelHint = activeModelHint
            )
        }

        /**
         * 按指定周期筛选条件（5h 或周）并结合当前活跃模型族选出对应的主展示配额桶。
         */
        private fun selectPrimaryPeriodBucket(
            buckets: List<QuotaBucketEntry>,
            activeModelHint: String?,
            periodPredicate: (QuotaBucketEntry) -> Boolean
        ): QuotaBucketEntry? {
            val periodBuckets = buckets.filter(periodPredicate)
            if (periodBuckets.isEmpty()) return null
            val modelMatched = periodBuckets.filter { matchesActiveModel(it.name, activeModelHint) }
            return (modelMatched.ifEmpty { periodBuckets }).minByOrNull { it.remainingPercent }
        }

        /**
         * 判断配额桶名称是否属于 5 小时周期配额。
         */
        private fun isFiveHourBucket(bucketName: String): Boolean {
            val lower = bucketName.lowercase(Locale.ROOT)
            return lower.endsWith("-5h") || lower.contains("5h") || lower.contains("5_hour") || lower.contains("five_hour")
        }

        /**
         * 判断配额桶名称是否属于周周期配额。
         */
        private fun isWeeklyBucket(bucketName: String): Boolean {
            val lower = bucketName.lowercase(Locale.ROOT)
            return lower.endsWith("-weekly") || lower.contains("weekly") || lower.contains("7d") || lower.contains("seven_day")
        }

        /**
         * 判断配额桶是否与当前活跃模型族匹配（Gemini 系列匹配 `gemini`，第三方模型匹配 `3p` 或显式前缀）。
         */
        private fun matchesActiveModel(bucketName: String, activeModelHint: String?): Boolean {
            if (activeModelHint.isNullOrBlank()) return false
            val lowerBucket = bucketName.lowercase(Locale.ROOT)
            val lowerModel = activeModelHint.lowercase(Locale.ROOT)
            if (lowerModel.contains("gemini")) {
                return lowerBucket.contains("gemini")
            }
            if (lowerBucket.startsWith("3p")) {
                return true
            }
            val prefix = lowerBucket.substringBefore('-')
            return prefix.length >= 2 && lowerModel.contains(prefix)
        }

        /**
         * 从 `agy models` 标准输出中提取所有非 Gemini 模型的厂商前缀（如 `CLAUDE&GPT`），避免在代码中写死第三方品牌名。
         */
        internal fun parseThirdPartyModelsLabel(modelsOutput: String): String? {
            val families = linkedSetOf<String>()
            for (rawLine in modelsOutput.lineSequence()) {
                if (!rawLine.contains('\t')) continue
                val modelId = rawLine.substringBefore('\t').trim()
                val displayName = rawLine.substringAfter('\t').trim()
                if (modelId.isEmpty()) continue
                val lowerId = modelId.lowercase(Locale.ROOT)
                val lowerDisplay = displayName.lowercase(Locale.ROOT)
                if (lowerId.startsWith("gemini") || lowerDisplay.contains("gemini") || lowerId.contains("orchestrator")) {
                    continue
                }
                val familyToken = modelId.substringBefore('-').trim()
                    .ifEmpty { displayName.substringBefore(' ').trim() }
                    .uppercase(Locale.ROOT)
                if (familyToken.isNotEmpty()) {
                    families.add(familyToken)
                }
            }
            if (families.isEmpty()) return null
            return families.joinToString("&")
        }

        /**
         * 将配额桶原始标识转换为可读展示名称：将 `3p` 替换为动态提取的第三方模型名，并将 `gemini` 全大写为 `GEMINI`。
         */
        private fun formatQuotaDisplayName(bucketName: String, thirdPartyLabel: String): String {
            val lower = bucketName.lowercase(Locale.ROOT)
            return when {
                lower == "3p" -> thirdPartyLabel
                lower.startsWith("3p-") -> "$thirdPartyLabel-" + bucketName.substring(3)
                lower == "gemini" -> "GEMINI"
                lower.startsWith("gemini-") -> "GEMINI-" + bucketName.substring(7)
                else -> bucketName
            }
        }

        /**
         * 将全部配额桶按“当前模型优先、5h 周期优先”顺序格式化为对齐的 HTML 表格明细，供鼠标悬停查看。
         */
        private fun formatAllQuotasHtml(
            buckets: List<QuotaBucketEntry>,
            activeModelHint: String?,
            thirdPartyLabel: String
        ): String {
            val ordered = buckets.sortedWith(
                compareBy<QuotaBucketEntry>(
                    { !matchesActiveModel(it.name, activeModelHint) },
                    { !isFiveHourBucket(it.name) },
                    { it.name.lowercase(Locale.ROOT) }
                )
            )
            val rowsHtml = ordered.joinToString("") { bucket ->
                val pctText = formatCompactPercent(bucket.remainingPercent)
                val safeName = StringUtil.escapeXmlEntities(formatQuotaDisplayName(bucket.name, thirdPartyLabel))
                val safePct = StringUtil.escapeXmlEntities(pctText)
                val resetCell = if (bucket.resetLabel != null) {
                    val safeReset = StringUtil.escapeXmlEntities(bucket.resetLabel)
                    "<td nowrap style=\"color: #8C8C8C;\">$safeReset</td>"
                } else {
                    "<td></td>"
                }
                "<tr>" +
                    "<td nowrap style=\"padding-right: 12px;\">$safeName</td>" +
                    "<td nowrap align=\"right\" style=\"padding-right: 10px;\"><b>$safePct</b></td>" +
                    resetCell +
                "</tr>"
            }
            return "<table cellspacing=\"0\" cellpadding=\"1\">$rowsHtml</table>"
        }

        /**
         * 解析配额桶距离下次刷新的剩余秒数（优先读取 `reset_in_seconds`，否则基于 `reset_time` 计算）。
         */
        private fun resolveResetSeconds(obj: JsonObject): Long? {
            val directSeconds = obj.getLongOrNull("reset_in_seconds")
            if (directSeconds != null) {
                return maxOf(0L, directSeconds)
            }
            val resetIso = obj.getStringOrNull("reset_time") ?: return null
            return runCatching {
                val target = Instant.parse(resetIso)
                maxOf(0L, Duration.between(Instant.now(), target).seconds)
            }.getOrNull()
        }

        /**
         * 将刷新剩余秒数格式化为紧凑的多语言倒计时文案。
         */
        private fun formatResetCountdown(seconds: Long): String {
            if (seconds < 60L) {
                return AgyBundle.message("statusbar.metrics.resetSoon")
            }
            val totalMinutes = seconds / 60L
            val days = totalMinutes / (24L * 60L)
            val hours = (totalMinutes % (24L * 60L)) / 60L
            val minutes = totalMinutes % 60L
            return when {
                days > 0L -> AgyBundle.message("statusbar.metrics.resetDayHour", days, hours)
                hours > 0L -> AgyBundle.message("statusbar.metrics.resetHourMin", hours, minutes)
                else -> AgyBundle.message("statusbar.metrics.resetMin", minutes)
            }
        }

        /**
         * 单个配额桶的解析结果。
         */
        internal data class QuotaBucketEntry(
            val name: String,
            val remainingPercent: Double,
            val resetLabel: String?
        )

        /**
         * 从 JsonObject 安全读取子对象；当字段缺失或为 JsonNull 时返回 null，避免强转崩溃。
         */
        private fun JsonObject.getObjectOrNull(key: String): JsonObject? {
            val el = get(key) ?: return null
            return if (el.isJsonObject) el.asJsonObject else null
        }

        /**
         * 从 JsonObject 安全读取字符串，字段缺失或非字符串时返回 null。
         */
        private fun JsonObject.getStringOrNull(key: String): String? {
            val el = get(key) ?: return null
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
        }

        /**
         * 从 JsonObject 安全读取布尔值，字段缺失或非布尔值时返回 null。
         */
        private fun JsonObject.getBooleanOrNull(key: String): Boolean? {
            val el = get(key) ?: return null
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) el.asBoolean else null
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
                        val detail = "${tokenMatch.groupValues[1]}/${tokenMatch.groupValues[2]} ($pctText)"
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
