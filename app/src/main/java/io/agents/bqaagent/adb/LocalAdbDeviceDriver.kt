// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import io.agents.bqaagent.ClawApplication
import io.agents.bqaagent.utils.UiTextMatchUtils
import io.agents.bqaagent.utils.XLog
import io.agents.bqaagent.fallback.FallbackRegistry
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

object LocalAdbDeviceDriver {
    private const val TAG = "LocalAdbDeviceDriver"
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val SCREEN_TREE_CACHE_MAX_AGE_MS = 2_000L
    private const val DETAIL_MAX_NODES = 250

    private val nodeIdMap = ConcurrentHashMap<String, UiNode>()
    private val nodeCounter = AtomicInteger(0)
    private val snapshotCounter = AtomicInteger(0)
    private val screenTreeCacheLock = Any()
    private var screenTreeCache: ScreenTreeCache? = null

    /** Package the nodeIdMap was captured from; blank when unknown or empty. */
    @Volatile
    private var nodeMapPackage = ""

    private data class ScreenTreeCache(
        val createdAtMs: Long,
        val snapshotId: String,
        val nodeMap: Map<String, UiNode>,
        val nodes: List<UiNode>,
        val quality: DumpQuality,
        val foreground: ForegroundWindow,
    )

    private data class ForegroundWindow(
        val packageName: String = "",
        val activityName: String = "",
        val raw: String = "",
    )

    private enum class ScreenInfoMode {
        COMPACT,
        DETAIL,
        TEXT,
        FULL;

        companion object {
            fun from(raw: String?): ScreenInfoMode {
                return values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: COMPACT
            }
        }
    }

    private data class DumpQuality(
        val ok: Boolean,
        val reason: String = "",
        val packageName: String = "",
        val activityName: String = "",
        val nodeCount: Int = 0,
        val addressableCount: Int = 0,
    )

    private data class UiRow(
        val top: Int,
        val bottom: Int,
        val nodes: List<UiNode>,
    )

    @JvmStatic
    fun isReady(): Boolean = LocalAdbAutomation.isReady()

    @JvmStatic
    fun awaitReady(timeoutMs: Long = 20_000L): Boolean = LocalAdbAutomation.awaitReady(timeoutMs)

    @JvmStatic
    fun performTap(x: Int, y: Int): Boolean {
        return execOk("input tap $x $y")
    }

    @JvmStatic
    fun performLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        return execOk("input swipe $x $y $x $y ${durationMs.coerceAtLeast(1L)}")
    }

    @JvmStatic
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        return execOk("input swipe $startX $startY $endX $endY ${durationMs.coerceAtLeast(1L)}")
    }

    @JvmStatic
    fun sendKeyEvent(keyCode: Int): Boolean {
        return execOk("input keyevent $keyCode")
    }

    @JvmStatic
    fun pressBack(): Boolean = sendKeyEvent(KeyEvent.KEYCODE_BACK)

    @JvmStatic
    fun pressHome(): Boolean = sendKeyEvent(KeyEvent.KEYCODE_HOME)

    @JvmStatic
    fun openRecentApps(): Boolean = sendKeyEvent(KeyEvent.KEYCODE_APP_SWITCH)

    @JvmStatic
    fun expandNotifications(): Boolean = execOk("cmd statusbar expand-notifications")

    @JvmStatic
    fun collapseNotifications(): Boolean = execOk("cmd statusbar collapse")

    @JvmStatic
    fun lockScreen(): Boolean = sendKeyEvent(KeyEvent.KEYCODE_POWER)

    @JvmStatic
    fun unlockScreen(): Boolean {
        sendKeyEvent(KeyEvent.KEYCODE_WAKEUP)
        Thread.sleep(500)
        val size = screenSize()
        return performSwipe(size[0] / 2, (size[1] * 0.8f).toInt(), size[0] / 2, (size[1] * 0.2f).toInt(), 300)
    }

    @JvmStatic
    fun openApp(packageName: String): Boolean {
        val quoted = shellQuote(packageName)
        val result = LocalAdbAutomation.exec("monkey -p $quoted -c android.intent.category.LAUNCHER 1", 10_000L)
        if (result.isSuccess) invalidateScreenCache("open_app")
        return result.isSuccess
    }

    @JvmStatic
    fun openUrl(url: String, packageName: String?): Boolean {
        val normalizedUrl = normalizeUrl(url)
        val targetPackage = packageName?.trim().orEmpty()
        val command = buildString {
            append("am start -a android.intent.action.VIEW -d ")
            append(shellQuote(normalizedUrl))
            if (targetPackage.isNotBlank()) {
                append(" -p ")
                append(shellQuote(targetPackage))
            }
        }
        val result = LocalAdbAutomation.exec(command, 10_000L)
        if (result.isSuccess) invalidateScreenCache("open_url")
        return result.isSuccess
    }

    @JvmStatic
    fun takeScreenshotFile(): File? {
        val dir = File(ClawApplication.instance.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.png")
        val result = LocalAdbAutomation.exec("screencap -p ${shellQuote(file.absolutePath)}", 15_000L)
        if (!result.isSuccess || !file.exists() || file.length() <= 0) {
            XLog.w(TAG, "screencap failed: ${result.combinedOutput}")
            return null
        }
        return file
    }

    @JvmStatic
    fun getScreenTree(): String? {
        return buildScreenTree(ScreenInfoMode.COMPACT)
    }

    @JvmStatic
    fun getScreenTreeFull(): String? {
        return buildScreenTree(ScreenInfoMode.FULL)
    }

    @JvmStatic
    fun getScreenTree(mode: String?): String? {
        return buildScreenTree(ScreenInfoMode.from(mode))
    }

    @JvmStatic
    fun invalidateScreenCache() {
        invalidateScreenCache("external")
    }

    @JvmStatic
    fun getNodeCoordinates(nodeId: String): IntArray? {
        val node = nodeIdMap[nodeId.replace("[", "").replace("]", "").trim()] ?: return null
        return intArrayOf(node.centerX, node.centerY)
    }

    @JvmStatic
    fun getNode(nodeId: String): UiNode? {
        return nodeIdMap[nodeId.replace("[", "").replace("]", "").trim()]
    }

    /** Scrollable containers visible on the current screen, largest first. */
    @JvmStatic
    @JvmOverloads
    fun findScrollableRegions(refresh: Boolean = false): List<UiNode> {
        val nodes = if (refresh || nodeIdMap.isEmpty()) dumpNodes(assignIds = true) else currentMappedNodes()
        return findScrollAreas(nodes)
    }

    /** Visible, meaningfully-sized scrollable containers (filters out tiny spinners). */
    private fun findScrollAreas(nodes: List<UiNode>): List<UiNode> {
        val size = screenSize()
        val minArea = (size[0].toLong() * size[1] * 0.08f).toLong()
        return nodes
            .filter { it.scrollable && it.hasVisibleBounds() && it.bounds.width() >= 60 && it.bounds.height() >= 60 }
            .filter { it.bounds.width().toLong() * it.bounds.height() >= minArea }
            .sortedByDescending { it.bounds.width().toLong() * it.bounds.height() }
            .take(3)
    }

    @JvmStatic
    fun nodeMapPackage(): String = nodeMapPackage

    /** Force a fresh uiautomator dump and rebuild nodeIdMap; returns all parsed nodes. */
    @JvmStatic
    fun refreshNodeMap(): List<UiNode> = dumpNodes(assignIds = true)

    @JvmStatic
    @JvmOverloads
    fun findNodesByText(text: String, refresh: Boolean = true): List<UiNode> {
        val queries = splitQueries(text)
        if (queries.isEmpty()) return emptyList()
        val nodes = if (refresh || nodeIdMap.isEmpty()) {
            dumpNodes(assignIds = true)
        } else {
            currentMappedNodes()
        }
        val matched = nodes.filter { node ->
            queries.any { query ->
                UiTextMatchUtils.matchesExactOrNormalized(node.text, query) ||
                        UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, query) ||
                        UiTextMatchUtils.matchesRelaxed(node.text, query) ||
                        UiTextMatchUtils.matchesRelaxed(node.contentDescription, query)
            }
        }

        // Fallback: ADB 未找到匹配节点时，从预定义配置中查找
        if (matched.isEmpty()) {
            val fallbackNodes = FallbackRegistry.getInstance().queryByFuzzyText(text)
            if (fallbackNodes.isNotEmpty()) {
                XLog.i(TAG, "findNodesByText fallback hit: text=\"$text\" → ${fallbackNodes.size} node(s)")
                return fallbackNodes
            }
        }

        return matched
    }

    @JvmStatic
    fun currentMappedNodes(): List<UiNode> {
        return nodeIdMap.values.sortedBy { node ->
            node.nodeId.removePrefix("n").toIntOrNull() ?: Int.MAX_VALUE
        }
    }

//    @JvmStatic
//    fun currentMappedNodes(): List<UiNode> {
//        val nodes = nodeIdMap.values.sortedBy { node ->
//            node.nodeId.removePrefix("n").toIntOrNull() ?: Int.MAX_VALUE
//        }
//
//        // Fallback: 当 ADB 节点数过少（dump 不完整）时，追加预定义配置中的组件
//        if (nodes.size < BAD_TREE_MIN_NODES) {
//            val fallbackNodes = FallbackRegistry.getInstance().queryAllForCurrentApp()
//            if (fallbackNodes.isNotEmpty()) {
//                XLog.i(TAG, "currentMappedNodes fallback supplement: ${fallbackNodes.size} node(s) added")
//                return nodes + fallbackNodes
//            }
//        }
//
//        return nodes
//    }

    @JvmStatic
    fun findRecentLogcatLinesContaining(
        queries: List<String>,
        maxLines: Int = 500,
        timeoutMs: Long = 3_000L
    ): List<String> {
        val normalizedQueries = queries.map { it.lowercase() }.filter { it.isNotBlank() }
        if (normalizedQueries.isEmpty()) return emptyList()
        val safeLineCount = maxLines.coerceIn(50, 2000)
        val result = LocalAdbAutomation.exec("logcat -d -t $safeLineCount", timeoutMs)
        if (!result.isSuccess) return emptyList()
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                val lower = line.lowercase()
                normalizedQueries.any { lower.contains(it) }
            }
            .distinct()
            .toList()
            .takeLast(20)
    }

    @JvmStatic
    @JvmOverloads
    fun findFirstNodeByIdsOrText(
        viewIds: List<String>,
        texts: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): UiNode? {
        val idSet = viewIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val queries = texts.flatMap { splitQueries(it) }
        if (idSet.isEmpty() && queries.isEmpty()) return null

        return dumpNodes(assignIds = true, timeoutMs = timeoutMs).firstOrNull { node ->
            if (!node.enabled) return@firstOrNull false
            (node.resourceId.isNotBlank() && node.resourceId in idSet) ||
                queries.any { query ->
                    UiTextMatchUtils.matchesExactOrNormalized(node.text, query) ||
                        UiTextMatchUtils.matchesExactOrNormalized(node.contentDescription, query)
                }
        }
    }

    @JvmStatic
    fun findNodesById(viewId: String): List<UiNode> {
        val query = viewId.trim()
        if (query.isEmpty()) return emptyList()
        return dumpNodes(assignIds = true).filter { it.resourceId == query }
    }

    @JvmStatic
    fun getNodeDetail(node: UiNode): String {
        return buildString {
            append("class=").append(node.className)
            if (node.text.isNotBlank()) append(", text=\"").append(node.text).append("\"")
            if (node.contentDescription.isNotBlank()) append(", desc=\"").append(node.contentDescription).append("\"")
            if (node.resourceId.isNotBlank()) append(", id=\"").append(node.resourceId).append("\"")
            append(", clickable=").append(node.clickable)
            append(", enabled=").append(node.enabled)
            append(", bounds=").append(node.bounds.toShortString())
        }
    }

    @JvmStatic
    fun inputText(text: String, nodeId: String?, clearFirst: Boolean): Boolean {
        val normalizedNodeId = nodeId?.replace("[", "")?.replace("]", "")?.trim().orEmpty()
        if (normalizedNodeId.isNotEmpty()) {
            val coords = getNodeCoordinates(normalizedNodeId) ?: return false
            performTap(coords[0], coords[1])
            Thread.sleep(300)
        }

        if (clearFirst) {
            clearFocusedText()
        }

        if (setClipboardText(text)) {
            val pasted = sendKeyEvent(KeyEvent.KEYCODE_PASTE)
            if (pasted) return true
        }

        return inputTextFallback(text)
    }

    @JvmStatic
    fun tapBestNode(nodes: List<UiNode>): Boolean {
        val node = nodes.firstOrNull { it.enabled } ?: nodes.firstOrNull() ?: return false
        return performTap(node.centerX, node.centerY)
    }

    @JvmStatic
    fun activePackageName(): String {
        return dumpNodes(assignIds = false).firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()
    }

    /**
     * 获取当前前台 Activity 的全限定类名。
     * 通过 dumpsys window 获取，用于 FallbackRegistry 的页面级过滤。
     *
     * @return Activity 类名（如 "com.hsbc.hkmb.app.ui.transfer.ConfirmActivity"），获取失败返回空字符串
     */
    @JvmStatic
    fun activeActivityName(): String {
        return foregroundWindowFast().activityName
    }

    @JvmStatic
    fun foregroundPackageName(): String {
        return foregroundWindowFast().packageName
    }

    @JvmStatic
    fun bottomEditableNode(): UiNode? {
        return dumpNodes(assignIds = true)
            .filter { it.isEditable && it.bounds.height() > 0 && it.bounds.width() > 0 }
            .maxByOrNull { it.centerY }
    }

    @JvmStatic
    fun findBestSendNode(): UiNode? {
        val keywords = listOf("send", "发送", "傳送", "送信", "发送消息", "send message")
        return dumpNodes(assignIds = true)
            .filter { node ->
                val label = (node.text + " " + node.contentDescription).trim()
                label.isNotBlank() && keywords.any { label.contains(it, ignoreCase = true) }
            }
            .maxWithOrNull(compareBy<UiNode> { it.centerY }.thenBy { it.centerX })
    }

    @JvmStatic
    fun topTextNodes(maxY: Int): List<UiNode> {
        return dumpNodes(assignIds = true).filter {
            it.bounds.top >= 0 && it.bounds.bottom <= maxY &&
                (it.text.isNotBlank() || it.contentDescription.isNotBlank())
        }
    }

    @JvmStatic
    fun allTextNodes(): List<UiNode> {
        return dumpNodes(assignIds = true).filter { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
    }

    @JvmStatic
    fun screenSize(): IntArray {
        val app = ClawApplication.instance
        val metrics = app.resources.displayMetrics
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    private fun buildScreenTree(mode: ScreenInfoMode): String? {
        getCachedScreenTree(mode)?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        val nodes = dumpNodes(assignIds = true)
        val snapshotId = "s${snapshotCounter.incrementAndGet()}"
        val foreground = foregroundWindowFast()
        val quality = assessDumpQuality(nodes, foreground)
        synchronized(screenTreeCacheLock) {
            screenTreeCache = ScreenTreeCache(
                createdAtMs = SystemClock.elapsedRealtime(),
                snapshotId = snapshotId,
                nodeMap = nodeIdMap.toMap(),
                nodes = nodes,
                quality = quality,
                foreground = foreground,
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val tree = renderScreenTree(nodes, mode, snapshotId, quality)
        XLog.i(
            TAG,
            "get_screen_info built mode=${mode.name.lowercase()} snapshot=$snapshotId quality=${if (quality.ok) "ok" else quality.reason} nodes=${nodes.size} chars=${tree.length} elapsed=${elapsed}ms"
        )
        return tree
    }

    private fun getCachedScreenTree(mode: ScreenInfoMode): String? {
        val now = SystemClock.elapsedRealtime()
        val cached = synchronized(screenTreeCacheLock) {
            val current = screenTreeCache ?: return@synchronized null
            if (now - current.createdAtMs <= SCREEN_TREE_CACHE_MAX_AGE_MS) current else null
        } ?: return null

        val currentForeground = foregroundWindowFast()
        if (!cached.isForForeground(currentForeground)) {
            invalidateScreenCache("foreground_changed")
            XLog.i(
                TAG,
                "get_screen_info cache miss: foreground changed cached=${cached.cacheForegroundKey()} current=${currentForeground.key()}"
            )
            return null
        }

        nodeIdMap.clear()
        nodeIdMap.putAll(cached.nodeMap)
        nodeCounter.set(cached.nodeMap.size)
        nodeMapPackage = cached.foreground.packageName.ifBlank { cached.quality.packageName }
        val tree = renderScreenTree(cached.nodes, mode, cached.snapshotId, cached.quality)
        XLog.i(
            TAG,
            "get_screen_info snapshot hit mode=${mode.name.lowercase()} snapshot=${cached.snapshotId} age=${now - cached.createdAtMs}ms nodes=${cached.nodeMap.size} chars=${tree.length}"
        )
        return tree
    }

    private fun ScreenTreeCache.isForForeground(current: ForegroundWindow): Boolean {
        val cachedPackage = foreground.packageName.ifBlank { quality.packageName }
        if (cachedPackage.isBlank() || current.packageName.isBlank()) return true
        if (cachedPackage != current.packageName) return false
        val cachedActivity = foreground.activityName.ifBlank { quality.activityName }
        return cachedActivity.isBlank() || current.activityName.isBlank() || cachedActivity == current.activityName
    }

    private fun ScreenTreeCache.cacheForegroundKey(): String {
        val packageName = foreground.packageName.ifBlank { quality.packageName }.ifBlank { "unknown" }
        val activityName = foreground.activityName.ifBlank { quality.activityName }.ifBlank { "unknown" }
        return "$packageName/$activityName"
    }

    private fun ForegroundWindow.key(): String {
        return "${packageName.ifBlank { "unknown" }}/${activityName.ifBlank { "unknown" }}"
    }

    private fun invalidateScreenCache(reason: String) {
        synchronized(screenTreeCacheLock) {
            if (screenTreeCache != null) {
                XLog.d(TAG, "get_screen_info cache invalidated: $reason")
            }
            screenTreeCache = null
        }
    }

    private fun renderScreenTree(
        nodes: List<UiNode>,
        mode: ScreenInfoMode,
        snapshotId: String,
        quality: DumpQuality,
    ): String {
        if (!quality.ok && mode != ScreenInfoMode.FULL) {
            return buildQualityGateMessage(snapshotId, quality)
        }
        return when (mode) {
            ScreenInfoMode.COMPACT -> buildCompactTree(nodes, snapshotId)
            ScreenInfoMode.DETAIL -> buildDetailTree(nodes, snapshotId)
            ScreenInfoMode.TEXT -> buildTextTree(nodes, snapshotId)
            ScreenInfoMode.FULL -> buildFullTree(nodes, snapshotId, quality)
        }
    }

    private fun buildHeader(mode: ScreenInfoMode, snapshotId: String, nodes: List<UiNode>): String {
        val packageName = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()
        return buildString {
            append("snapshot=").append(snapshotId)
                .append(" mode=").append(mode.name.lowercase())
                .append(" package=").append(packageName.ifBlank { "unknown" })
                .append(" nodes=").append(nodes.size).append('\n')
            val areas = findScrollAreas(nodes)
            if (areas.isNotEmpty()) {
                append("scroll_area: ")
                    .append(areas.joinToString("; ") { "[${it.nodeId}] bounds=${it.bounds.toShortString()}" })
                    .append(" (swipe gestures must start and end inside one of these areas)\n")
            }
        }
    }

    private fun buildQualityGateMessage(snapshotId: String, quality: DumpQuality): String {
        return buildString {
            append("SCREEN_TREE_UNUSABLE snapshot=").append(snapshotId)
            append(" reason=").append(quality.reason.ifBlank { "unknown" })
            append(" package=").append(quality.packageName.ifBlank { "unknown" })
            append(" activity=").append(quality.activityName.ifBlank { "unknown" })
            append(" nodes=").append(quality.nodeCount)
            append(" addressable=").append(quality.addressableCount)
            append('\n')
            append("Do not repeat get_screen_info without a state-changing action. Try wait, back, reopen the app, or finish with the limitation if the current app hides its UI tree.")
        }
    }

    private fun assessDumpQuality(nodes: List<UiNode>, foreground: ForegroundWindow): DumpQuality {
        val packageName = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()
            .ifBlank { foreground.packageName }
        // Mode-independent usability signal (Fix A): a dump is usable as long as it
        // exposes at least one addressable node (labelled / actionable / ProgressBar /
        // resource-id bearing) that is not a pure structural container. Sparse-but-real
        // screens (e.g. a dialog with 1-2 buttons) stay usable so the LLM can act on
        // them; only genuinely blank or hidden dumps (0 addressable nodes) are gated to
        // SCREEN_TREE_UNUSABLE -> VLM. Replaces the old node-count(<3) + compact-char
        // heuristics that false-positived on valid sparse screens and were always
        // measured against compact regardless of the mode actually requested.
        val addressableCount = nodes.count { it.shouldExposeInCompact() && !it.isStructuralContainer() }
        val reason = when {
            nodes.isEmpty() -> "empty_nodes"
            addressableCount == 0 -> "no_addressable_content"
            else -> ""
        }
        val activityName = if (reason.isEmpty()) "" else foreground.activityName
        return DumpQuality(
            ok = reason.isEmpty(),
            reason = reason,
            packageName = packageName,
            activityName = activityName,
            nodeCount = nodes.size,
            addressableCount = addressableCount,
        )
    }

    private fun foregroundWindowFast(): ForegroundWindow {
        val result = LocalAdbAutomation.exec(
            "dumpsys window | grep -E 'mCurrentFocus|topResumedActivity'",
            3_000L
        )
        val componentRegex = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/([^\\s}]+)")
        var firstRaw = ""
        for (line in result.stdout.lineSequence()) {
            val raw = line.trim()
            if (raw.isEmpty()) continue
            if (firstRaw.isEmpty()) firstRaw = raw
            val component = componentRegex.findAll(raw).lastOrNull()?.value.orEmpty()
            if (component.isNotEmpty()) {
                return ForegroundWindow(
                    packageName = component.substringBefore('/', "").trim(),
                    activityName = component.substringAfter('/', "").trim(),
                    raw = raw
                )
            }
        }
        return ForegroundWindow(packageName = "", activityName = "", raw = firstRaw)
    }

    private fun buildCompactTree(nodes: List<UiNode>, snapshotId: String): String {
        val rows = buildRows(nodes.filter { it.shouldExposeInCompact() && !it.isStructuralContainer() })
        return buildString {
            append(buildHeader(ScreenInfoMode.COMPACT, snapshotId, nodes))
            for ((index, row) in rows.withIndex()) {
                val visibleNodes = row.nodes
                if (visibleNodes.isEmpty()) continue
                // Plan-A: every addressable element in the row gets its OWN node id + tap
                // coordinates, so a row of buttons exposes button 1/2/3... individually
                // instead of only the first actionable one.
                val addressable = visibleNodes.filter { it.isActionable() || it.visibleLabel().isNotBlank() }
                val targets = (if (addressable.isNotEmpty()) addressable else visibleNodes).take(8)
                append("[row").append(index + 1).append("] ")
                targets.forEachIndexed { nodeIndex, node ->
                    if (nodeIndex > 0) append("  ")
                    val label = sanitizeLabel(node.enrichedLabel(nodes))
                    append("[").append(node.nodeId).append("] ")
                    if (label.isNotBlank()) {
                        append('"').append(label.take(48)).append(if (label.length > 48) ".." else "").append('"')
                    } else {
                        append(node.classShortName())
                    }
                    append(node.flagsString())
                    append(" tap=(").append(node.centerX).append(',').append(node.centerY).append(")")
                }
                append(" y=").append(row.top).append("..").append(row.bottom)
                append('\n')
            }
        }
    }

    private fun buildDetailTree(nodes: List<UiNode>, snapshotId: String): String {
        val candidates = nodes
            .filter { it.shouldExposeInCompact() && !it.isStructuralContainer() }
            .sortedWith(compareBy<UiNode> { it.bounds.top }.thenBy { it.bounds.left })
            .map { it to it.enrichedLabel(nodes) }
        val deduped = dedupeDetailNodes(candidates).take(DETAIL_MAX_NODES)
        return buildString {
            append(buildHeader(ScreenInfoMode.DETAIL, snapshotId, nodes))
            for ((node, label) in deduped) {
                appendNodeLine(
                    node,
                    includeClass = true,
                    includeBounds = true,
                    full = false,
                    labelOverride = label,
                )
            }
        }
    }

    /**
     * Detail-mode de-duplication (Fix 1 + Fix 4). HSBC-style screens nest 4-5 wrappers
     * around ONE logical element and every clickable / resource-id-bearing wrapper
     * passes the expose filter, so the raw dump renders the same button or account row
     * several times at the same tap point.
     *  - Fix 4: nodes with IDENTICAL bounds are one visual element -> keep a single
     *    representative (prefer clickable, then resource-id, then a labelled one).
     *  - Fix 1: an ancestor and a descendant carrying the SAME non-blank label are the
     *    same element -> keep the better tap target (higher rank, else the outer one).
     * Info children with their own bounds/labels (title / detail / amount TextViews)
     * always survive, so no real content is lost.
     */
    private fun dedupeDetailNodes(items: List<Pair<UiNode, String>>): List<Pair<UiNode, String>> {
        fun rank(node: UiNode): Int =
            (if (node.clickable) 2 else 0) + (if (node.resourceId.isNotBlank()) 1 else 0)
        fun area(node: UiNode): Long = node.bounds.width().toLong() * node.bounds.height().toLong()

        // Fix 4: collapse identical-bounds stacks to one representative.
        val byBounds = LinkedHashMap<String, MutableList<Pair<UiNode, String>>>()
        for (item in items) {
            val b = item.first.bounds
            byBounds.getOrPut("${b.left},${b.top},${b.right},${b.bottom}") { mutableListOf() }.add(item)
        }
        val afterBounds = ArrayList<Pair<UiNode, String>>()
        for (group in byBounds.values) {
            if (group.size == 1) {
                afterBounds.add(group[0])
            } else {
                val rep = group.maxWithOrNull(
                    compareBy<Pair<UiNode, String>> { rank(it.first) }
                        .thenBy { if (it.second.isNotBlank()) 1 else 0 }
                ) ?: group[0]
                afterBounds.add(rep)
            }
        }

        // Fix 1: drop a node when a surviving node contains it with the SAME non-blank
        // label and is an equal-or-better representative (higher rank, else larger/outer).
        val result = ArrayList<Pair<UiNode, String>>()
        for (candidate in afterBounds) {
            val node = candidate.first
            val label = candidate.second
            val dominated = label.isNotBlank() && afterBounds.any { other ->
                if (other === candidate) return@any false
                val oNode = other.first
                other.second == label &&
                        oNode.bounds.contains(node.bounds) &&
                        (rank(oNode) > rank(node) ||
                                (rank(oNode) == rank(node) && area(oNode) >= area(node)))
            }
            if (!dominated) result.add(candidate)
        }
        return result.sortedWith(
            compareBy<Pair<UiNode, String>> { it.first.bounds.top }.thenBy { it.first.bounds.left }
        )
    }

    private fun buildTextTree(nodes: List<UiNode>, snapshotId: String): String {
        val rows = buildRows(nodes.filter { it.visibleLabel().isNotBlank() && !it.isStructuralContainer() })
        return buildString {
            append(buildHeader(ScreenInfoMode.TEXT, snapshotId, nodes))
            for ((index, row) in rows.withIndex()) {
                val labels = row.nodes.mapNotNull { it.visibleLabel().takeIf { label -> label.isNotBlank() } }
                    .distinct()
                if (labels.isEmpty()) continue
                append("[text").append(index + 1).append("] ")
                append(labels.joinToString(" | ") { "\"${it.take(80)}${if (it.length > 80) ".." else ""}\"" })
                append(" y=").append(row.top).append("..").append(row.bottom)
                append('\n')
            }
        }
    }

    private fun buildFullTree(nodes: List<UiNode>, snapshotId: String, quality: DumpQuality): String {
        return buildString {
            append(buildHeader(ScreenInfoMode.FULL, snapshotId, nodes))
            if (!quality.ok) {
                append("quality=").append(quality.reason).append(" addressable=").append(quality.addressableCount).append('\n')
                // Deleted:append("quality=").append(quality.reason).append(" chars=").append(quality.outputChars).append('\n')
            }
            for (node in nodes) {
                appendNodeLine(node, includeClass = true, includeBounds = true, full = true)
            }
        }
    }

    private fun StringBuilder.appendNodeLine(
        node: UiNode,
        includeClass: Boolean,
        includeBounds: Boolean = true,
        full: Boolean = false,
        labelOverride: String? = null,
    ) {
        append("[").append(node.nodeId.ifBlank { "node" }).append("] ")
        if (includeClass) append(node.classShortName()).append(' ')
        val label = sanitizeLabel(labelOverride ?: node.visibleLabel())
        if (label.isNotBlank()) append('"').append(label.take(if (full) 200 else 80)).append(if (!full && label.length > 80) ".." else "").append("\" ")
        append(node.flagsString())
        append(" tap=(").append(node.centerX).append(',').append(node.centerY).append(")")
        if (node.resourceId.isNotBlank()) append(" id=").append(node.resourceId.substringAfterLast('/'))
        if (includeBounds) append(" bounds=").append(node.bounds.toShortString())
        if (full && node.packageName.isNotBlank()) append(" pkg=").append(node.packageName)
        append('\n')
    }

    private fun buildRows(nodes: List<UiNode>): List<UiRow> {
        val sorted = nodes
            .filter { it.hasVisibleBounds() }
            .sortedWith(compareBy<UiNode> { it.bounds.top }.thenBy { it.bounds.left })
        val rows = mutableListOf<MutableList<UiNode>>()
        for (node in sorted) {
            val target = rows.firstOrNull { existing ->
                existing.any { rowNode -> verticalOverlapRatio(rowNode.bounds, node.bounds) >= 0.45f } ||
                    kotlin.math.abs(existing.first().centerY - node.centerY) <= 32
            }
            if (target != null) {
                target.add(node)
            } else {
                rows.add(mutableListOf(node))
            }
        }
        return rows.map { rowNodes ->
            val ordered = rowNodes.sortedBy { it.bounds.left }
            UiRow(
                top = ordered.minOf { it.bounds.top },
                bottom = ordered.maxOf { it.bounds.bottom },
                nodes = ordered
            )
        }.sortedBy { it.top }
    }

    private fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val minHeight = minOf(a.height(), b.height()).coerceAtLeast(1)
        return overlap.toFloat() / minHeight.toFloat()
    }

    private fun nearestLabelFor(edit: UiNode, nodes: List<UiNode>): String {
        val candidates = nodes
            .filter { it !== edit && it.visibleLabel().isNotBlank() && !it.isEditable }
            .filter { label ->
                val sameRow = verticalOverlapRatio(label.bounds, edit.bounds) >= 0.25f
                val above = label.bounds.bottom <= edit.bounds.top && edit.bounds.top - label.bounds.bottom <= 180
                val leftOrAligned = label.bounds.left <= edit.bounds.right && label.bounds.right >= edit.bounds.left - 40
                (sameRow || above) && leftOrAligned
            }
        return candidates.minByOrNull { label ->
            kotlin.math.abs(label.centerY - edit.centerY) + kotlin.math.abs(label.centerX - edit.centerX) / 4
        }?.visibleLabel().orEmpty()
    }

    /**
     * Contained label for a nameless node, borrowed from its descendants ONLY when it
     * is unambiguous. Mirrors the coordinate -> text attribution in
     * SkillRecorder.lookupTextByCoordinate so the driver render and the recorded
     * NodeLocator share ONE label algorithm.
     *
     * Fix 2: a container that wraps MULTIPLE different labels (e.g. a row holding
     * "Add a new payee" + "All payees", or a tab pager holding all four tabs) is a
     * GROUP, not a single element. Inheriting one arbitrary child's label misleads the
     * LLM into tapping the wrong thing, so we only borrow the label when every labelled
     * descendant agrees on ONE distinct value; otherwise return blank and let the render
     * fall back to the class short name.
     */
    @JvmStatic
    fun containedLabelFor(node: UiNode, nodes: List<UiNode>): String {
        val distinct = nodes
            .filter { it !== node && it.label.isNotBlank() && node.bounds.contains(it.bounds) }
            .map { it.label.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (distinct.size == 1) distinct[0] else ""
    }

    /**
     * Best display label for a node: own text/desc first, then the smallest contained
     * labelled descendant, then (for input fields) the nearest associated label.
     * Returns blank when nothing meaningful is found; the caller applies its own
     * fallback (resource-id / class short name).
     */
    private fun UiNode.enrichedLabel(nodes: List<UiNode>): String {
        val self = visibleLabel()
        if (self.isNotBlank()) return self
        val contained = containedLabelFor(this, nodes)
        if (contained.isNotBlank()) return contained
        if (isEditable) {
            val near = nearestLabelFor(this, nodes)
            if (near.isNotBlank()) return near
        }
        return ""
    }

    private fun UiNode.visibleLabel(): String = text.ifBlank { contentDescription }.trim()

    /**
     * Fix 3: collapse newlines / runs of whitespace in a label to single spaces so an
     * accessibility description (e.g. HSBC's "Current Account\n[1, , 4, ...]") cannot
     * break the one-node-per-line detail/compact format. Render-time only — the raw
     * text used for replay matching (containedLabelFor / NodeLocator) stays untouched.
     */
    private fun sanitizeLabel(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    private fun UiNode.classShortName(): String = className.substringAfterLast('.').ifBlank { "node" }

    private fun UiNode.isActionable(): Boolean {
        return clickable || scrollable || isEditable || checkable || longClickable || focused || selected
    }

    private fun UiNode.shouldExposeInCompact(): Boolean {
        val hasLabel = visibleLabel().isNotBlank()
        val progress = className.contains("ProgressBar")
        return hasLabel || isActionable() || progress || resourceId.isNotBlank()
    }

    private fun UiNode.isStructuralContainer(): Boolean {
        if (isEditable || clickable || checkable || longClickable) return false
        val size = screenSize()
        val largeWidth = bounds.width() >= (size[0] * 0.85f).toInt()
        val largeHeight = bounds.height() >= (size[1] * 0.25f).toInt()
        if (!largeWidth || !largeHeight) return false
        return visibleLabel().isBlank() || scrollable || className.contains("WebView")
    }

    private fun UiNode.flagsString(): String {
        return buildString {
            if (clickable) append(" tap")
            if (isEditable) append(" edit")
            if (scrollable) append(" scroll")
            if (longClickable) append(" long")
            if (checkable) append(if (checked) " checked" else " unchecked")
            if (focused) append(" focused")
            if (selected) append(" selected")
            if (!enabled) append(" disabled")
        }
    }

    private fun dumpNodes(assignIds: Boolean, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<UiNode> {
        val xml = dumpWindowXml(timeoutMs) ?: return emptyList()
        return parseNodes(xml, assignIds)
    }

    /**
     * 升级为 UiAutomator2 --compressed 压缩抓取，无临时文件IO，速度更快、XML体积更小
     * Android 8.0+ 支持，低版本自动降级 v1 旧方案
     */
    private fun dumpWindowXml(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val appContext = ClawApplication.instance.applicationContext
        val cacheDir = appContext.getExternalFilesDir("uiautomator")
        cacheDir?.mkdirs()
        val tempXmlFile = File(cacheDir, "ui_dump_temp.xml")
        val tempPath = shellQuote(tempXmlFile.absolutePath)

        // ========== UiAutomator2 优先：使用 --compressed 压缩输出（v2专属） ==========
        val uia2Command = "uiautomator dump --compressed $tempPath && cat $tempPath"
        val uia2Result = LocalAdbAutomation.exec(uia2Command, timeoutMs)
        if (uia2Result.isSuccess && uia2Result.stdout.isNotBlank()) {
            // 抓取成功，删除临时文件，返回压缩XML文本
            runCatching { tempXmlFile.delete() }
            return uia2Result.stdout
        }
        XLog.w(TAG, "UiAutomator2 compressed dump failed, fallback to old uiautomator v1")

        // ========== 降级兜底：原生 uiautomator v1 方案 ==========
        val v1Command = "uiautomator dump $tempPath && cat $tempPath"
        val v1Result = LocalAdbAutomation.exec(v1Command, timeoutMs)
        runCatching { tempXmlFile.delete() }

        if (!v1Result.isSuccess || v1Result.stdout.isBlank()) {
            XLog.e(TAG, "All uiautomator dump failed, output: ${v1Result.combinedOutput}")
            return null
        }
        return v1Result.stdout
    }

    private fun parseNodes(xml: String, assignIds: Boolean): List<UiNode> {
        return try {
            val startedAt = SystemClock.elapsedRealtime()
            if (assignIds) {
                nodeIdMap.clear()
                nodeCounter.set(0)
            }

            var rawText = xml.trim()
            if (rawText.isEmpty()) {
                XLog.w(TAG, "uiautomator xml raw text empty")
                return emptyList()
            }

            // 核心修复：截断头部无关dump提示文字，只保留<?xml开头及之后内容
            val xmlStartIndex = rawText.indexOf("<?xml")
            if (xmlStartIndex == -1) {
                XLog.w(TAG, "No <?xml header found in ui dump text")
                return emptyList()
            }
            var cleanXml = rawText.substring(xmlStartIndex)

            // 仅清理换行、制表符，不破坏标签与文字内部空格
            cleanXml = cleanXml.replace(Regex("[\\n\\t]"), "")
            // 连续多个空格压缩为单个，避免大量空白子节点
            cleanXml = cleanXml.replace(Regex(" {2,}"), " ")

            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (_: Exception) {
                }
            }
            // 使用截断清洗后的纯XML解析
            val inputStream = cleanXml.byteInputStream(Charsets.UTF_8)
            val doc = factory.newDocumentBuilder().parse(inputStream)
            inputStream.close()

            val result = mutableListOf<UiNode>()
            val filteredNodeCount = collectNodes(doc.documentElement, result, assignIds)
            if (assignIds) {
                nodeMapPackage = result.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: ""
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            XLog.i(
                TAG,
                "uiautomator XML parsed nodes=${result.size} filtered=$filteredNodeCount chars=${cleanXml.length} elapsed=${elapsed}ms"
            )
            result
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse uiautomator XML", e)
            if (assignIds) {
                nodeMapPackage = ""
            }
            emptyList()
        }
    }

    private fun collectNodes(domNode: Node, result: MutableList<UiNode>, assignIds: Boolean): Int {
        var filteredNodeCount = 0
        if (domNode is Element && domNode.tagName == "node") {
            val node = domNode.toUiNode(assignIds)
            if (node.hasVisibleBounds()) {
                result.add(node)
                if (assignIds) {
                    nodeIdMap[node.nodeId] = node
                }
            } else {
                filteredNodeCount++
            }
        }
        val children = domNode.childNodes
        for (i in 0 until children.length) {
            filteredNodeCount += collectNodes(children.item(i), result, assignIds)
        }
        return filteredNodeCount
    }

    private fun Element.toUiNode(assignId: Boolean): UiNode {
        val nodeId = if (assignId) "n${nodeCounter.incrementAndGet()}" else ""
        return UiNode(
            nodeId = nodeId,
            text = attr("text"),
            contentDescription = attr("content-desc"),
            resourceId = attr("resource-id"),
            className = attr("class"),
            packageName = attr("package"),
            bounds = parseBounds(attr("bounds")),
            clickable = attr("clickable").toBoolean(),
            longClickable = attr("long-clickable").toBoolean(),
            scrollable = attr("scrollable").toBoolean(),
            checkable = attr("checkable").toBoolean(),
            checked = attr("checked").toBoolean(),
            enabled = attr("enabled").ifBlank { "true" }.toBoolean(),
            focused = attr("focused").toBoolean(),
            selected = attr("selected").toBoolean(),
        )
    }

    private fun Element.attr(name: String): String = getAttribute(name) ?: ""

    private fun parseBounds(value: String): Rect {
        val match = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(value)
            ?: return Rect()
        val (left, top, right, bottom) = match.destructured
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun UiNode.hasVisibleBounds(): Boolean {
        val nodeBounds = this.bounds
        if (nodeBounds.isEmpty || nodeBounds.width() <= 0 || nodeBounds.height() <= 0) return false
        val size = screenSize()
        val screenRect = Rect(0, 0, size[0], size[1])
        return Rect.intersects(screenRect, nodeBounds)
    }

    private fun splitQueries(text: String): List<String> {
        return text.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @JvmStatic
    fun clearFocusedText() {
        clearFocusedText(120)
    }

    @JvmStatic
    fun clearFocusedText(maxDeletes: Int) {
        val safeDeletes = maxDeletes.coerceIn(1, 120)
        sendKeyEvent(KeyEvent.KEYCODE_MOVE_END)
        val command = "i=0; while [ \$i -lt $safeDeletes ]; do input keyevent ${KeyEvent.KEYCODE_DEL}; i=\$((i+1)); done"
        LocalAdbAutomation.exec(command, 10_000L)
        invalidateScreenCache("clear_focused_text")
    }

    private fun inputTextFallback(text: String): Boolean {
        val escaped = text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(";", "\\;")
        return execOk("input text $escaped", 10_000L)
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        val lower = trimmed.lowercase()
        return if (
            lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("about:") ||
            lower.startsWith("chrome:") ||
            lower.startsWith("file:")
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun setClipboardText(text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = ClawApplication.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("bqaagent_input", text))
                ok = true
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to set clipboard text", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun execOk(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val result = LocalAdbAutomation.exec(command, timeoutMs)
        if (!result.isSuccess) {
            XLog.w(TAG, "Command failed: $command -> ${result.combinedOutput}")
        } else {
            invalidateScreenCache(command.substringBefore(' '))
        }
        return result.isSuccess
    }

    @JvmStatic
    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
