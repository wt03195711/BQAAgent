// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import io.agents.bqaagent.R
import io.agents.bqaagent.utils.KVUtils
import io.agents.bqaagent.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

object LocalAdbAutomation {
    private const val TAG = "LocalAdbAutomation"
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    private const val READY_PROBE = "bqaagent_ready"
    private const val ACTION_WIRELESS_DEBUGGING_SETTINGS = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
    private const val READY_CACHE_TTL_MS = 15_000L
    private const val PAIR_TIMEOUT_MS = 15_000L
    private const val PORT_SCAN_MIN = 32_768
    private const val PORT_SCAN_MAX = 60_999
    private const val PORT_SCAN_THREADS = 96
    private const val PORT_SCAN_CONNECT_TIMEOUT_MS = 35
    private const val PORT_SCAN_TOTAL_TIMEOUT_MS = 12_000L
    private const val PORT_SCAN_MAX_RESULTS = 8
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    private const val WIRELESS_DEBUGGING_PREF_KEY = "toggle_adb_wireless"

    private val lock = Any()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var client: Kadb? = null

    @Volatile
    private var readyProbeRunning = false

    @Volatile
    private var lastReadyAt = 0L

    @Volatile
    private var lastFailedProbeAt = 0L

    data class SetupRequest(
        val host: String,
        val connectPort: Int,
        val pairPort: Int? = null,
        val pairingCode: String? = null,
    ) {
        val needsPairing: Boolean
            get() = pairPort != null && !pairingCode.isNullOrBlank()
    }

    data class PairingInput(
        val host: String,
        val pairPort: Int?,
        val pairingCode: String,
    )

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            ensureIdentityLocked()
            XLog.i(TAG, "Local ADB initialized")
        }
    }

    @JvmStatic
    fun hasConnectionConfig(): Boolean {
        return KVUtils.getLocalAdbHost().isNotBlank() && KVUtils.getLocalAdbPort() > 0
    }

    @JvmStatic
    fun hasRememberedPairing(): Boolean {
        val hasLegacyConnection = hasConnectionConfig()
        return hasSavedIdentity() && (KVUtils.isLocalAdbPaired() || hasLegacyConnection)
    }

    @JvmStatic
    fun isReady(): Boolean {
        if (hasRecentReadyProbe()) return true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshReadyAsync()
            return false
        }

        val existing = client
        if (existing?.connectionCheck() == true) {
            markReady()
            return true
        }
        return awaitReady(1_500L)
    }

    @JvmStatic
    fun awaitReady(timeoutMs: Long = 20_000L): Boolean {
        val probe = exec("echo $READY_PROBE", timeoutMs.coerceAtLeast(DEFAULT_CONNECT_TIMEOUT_MS.toLong()))
        val ready = probe.isSuccess && probe.stdout.trim() == READY_PROBE
        if (ready) {
            markReady()
        } else {
            lastFailedProbeAt = System.currentTimeMillis()
        }
        return ready
    }

    @JvmStatic
    fun hasRecentReadyProbe(now: Long = System.currentTimeMillis()): Boolean {
        return lastReadyAt > 0L && now - lastReadyAt <= READY_CACHE_TTL_MS
    }

    @JvmStatic
    fun hasRecentFailedProbe(now: Long = System.currentTimeMillis()): Boolean {
        return lastFailedProbeAt > 0L && lastFailedProbeAt >= lastReadyAt && now - lastFailedProbeAt <= READY_CACHE_TTL_MS
    }

    @JvmStatic
    fun refreshReadyAsync() {
        if (!hasConnectionConfig() || readyProbeRunning) return
        readyProbeRunning = true
        probeScope.launch {
            try {
                awaitReady(5_000L)
            } finally {
                readyProbeRunning = false
            }
        }
    }

    @JvmStatic
    fun exec(command: String, timeoutMs: Long = 15_000L): ShellCommandResult {
        return exec(command, timeoutMs, logFailure = true)
    }

    private fun exec(command: String, timeoutMs: Long = 15_000L, logFailure: Boolean): ShellCommandResult {
        val kadb = synchronized(lock) {
            ensureIdentityLocked()
            ensureClientLocked(timeoutMs)
        } ?: return ShellCommandResult(
            -1,
            false,
            "",
            "Local ADB is not configured. Enable Wireless debugging, pair BQAAgent, then enter the connect port.",
        )

        return try {
            val response = kadb.shell(command)
            ShellCommandResult(
                response.exitCode,
                false,
                response.output,
                response.errorOutput,
            )
        } catch (e: Throwable) {
            resetClient()
            if (logFailure) {
                XLog.w(TAG, "Local ADB command failed: $command", e)
            }
            ShellCommandResult(-1, false, "", e.message ?: e.toString())
        }
    }

    @JvmStatic
    fun shellUid(): Int {
        val result = exec("id -u", 3_000L)
        return result.stdout.trim().toIntOrNull() ?: -1
    }

    @JvmStatic
    fun configureConnection(host: String, connectPort: Int): ShellCommandResult {
        return configureConnection(host, connectPort, logFailure = true)
    }

    private fun configureConnection(host: String, connectPort: Int, logFailure: Boolean): ShellCommandResult {
        val cleanHost = host.ifBlank { DEFAULT_HOST }
        if (connectPort <= 0) {
            return ShellCommandResult(-1, false, "", "Invalid Local ADB connect port.")
        }
        KVUtils.setLocalAdbHost(cleanHost)
        KVUtils.setLocalAdbPort(connectPort)
        resetClient()
        val result = exec("echo $READY_PROBE", 10_000L, logFailure)
        if (result.isSuccess && hasSavedIdentity()) {
            KVUtils.setLocalAdbPaired(true)
            markReady()
        } else if (!result.isSuccess) {
            lastFailedProbeAt = System.currentTimeMillis()
        }
        return result
    }

    @JvmStatic
    fun pair(host: String, pairPort: Int, pairingCode: String): ShellCommandResult {
        if (pairPort <= 0 || pairPort > 65535) {
            return ShellCommandResult(-1, false, "", "Invalid Local ADB pairing port.")
        }
        if (pairingCode.isBlank()) {
            return ShellCommandResult(-1, false, "", "Invalid Local ADB pairing code.")
        }

        synchronized(lock) {
            ensureIdentityLocked()
        }

        val pairHosts = listOf(host.ifBlank { DEFAULT_HOST }, DEFAULT_HOST).distinct()
        val errors = mutableListOf<String>()
        for (pairHost in pairHosts) {
            try {
                XLog.i(TAG, "Local ADB pairing attempt host=$pairHost port=$pairPort")
                pairOnce(pairHost, pairPort, pairingCode)
                KVUtils.setLocalAdbPaired(true)
                KVUtils.setLocalAdbPairHost(pairHost)
                XLog.i(TAG, "Local ADB pairing succeeded host=$pairHost port=$pairPort")
                return ShellCommandResult(0, false, "", "")
            } catch (e: Throwable) {
                errors += "$pairHost:$pairPort -> ${formatPairingError(e)}"
                resetClient()
                XLog.w(TAG, "Local ADB pairing failed for host=$pairHost", e)
            }
        }

        return ShellCommandResult(-1, false, "", "Pairing failed:\n${errors.joinToString("\n")}")
    }

    private fun pairOnce(pairHost: String, pairPort: Int, pairingCode: String) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bqaagent-adb-pair").apply { isDaemon = true }
        }
        val future = executor.submit<Unit> {
            runBlocking {
                Kadb.pair(
                    pairHost,
                    pairPort,
                    pairingCode,
                    "BQAAgent",
                )
            }
        }
        try {
            future.get(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            XLog.w(TAG, "Local ADB pairing timed out host=$pairHost port=$pairPort", e)
            throw e
        } finally {
            executor.shutdownNow()
        }
    }

    @JvmStatic
    fun pairAndConfigure(request: SetupRequest): ShellCommandResult {
        if (request.connectPort <= 0) {
            return ShellCommandResult(-1, false, "", "Invalid Local ADB connect port.")
        }

        if (request.needsPairing) {
            val pairResult = pair(request.host, request.pairPort ?: 0, request.pairingCode.orEmpty())
            if (!pairResult.isSuccess) return pairResult
        }

        return configureConnection(request.host, request.connectPort)
    }

    @JvmStatic
    fun pairAndConfigureByDiscovery(
        context: Context,
        host: String,
        pairPort: Int,
        pairingCode: String,
    ): ShellCommandResult {
        val pairResult = pair(host, pairPort, pairingCode)
        if (!pairResult.isSuccess) return pairResult

        val discovery = LocalAdbDiscovery.discoverBlocking(context.applicationContext, timeoutMs = 7_000L)
        val connectPort = discovery.connectPort
        if (connectPort != null) {
            val connectHost = discovery.connectHost ?: discovery.host.ifBlank { DEFAULT_HOST }
            return configureConnection(connectHost, connectPort)
        }

        return scanAndConfigureConnection(listOf(DEFAULT_HOST, discovery.connectHost, discovery.host))
            ?: ShellCommandResult(-1, false, "", context.getString(R.string.local_adb_connect_port_missing))
    }

    @JvmStatic
    fun pairAndConfigureByPairingCodeDiscovery(
        context: Context,
        host: String,
        pairingCode: String,
    ): ShellCommandResult {
        if (pairingCode.isBlank()) {
            return ShellCommandResult(-1, false, "", "Invalid Local ADB pairing code.")
        }

        val appContext = context.applicationContext
        XLog.i(TAG, "Local ADB discovering pairing port")
        val firstDiscovery = LocalAdbDiscovery.discoverBlocking(appContext, timeoutMs = 10_000L)
        XLog.i(
            TAG,
            "Local ADB first discovery host=${firstDiscovery.host} pair=${firstDiscovery.pairHost}:${firstDiscovery.pairPort} connect=${firstDiscovery.connectHost}:${firstDiscovery.connectPort}"
        )
        val pairPort = firstDiscovery.pairPort
            ?: return ShellCommandResult(-1, false, "", context.getString(R.string.local_adb_pair_port_missing))
        val pairHost = if (host.isBlank() || host == DEFAULT_HOST) {
            firstDiscovery.pairHost ?: firstDiscovery.host
        } else {
            host
        }

        val pairResult = pair(pairHost.ifBlank { DEFAULT_HOST }, pairPort, pairingCode)
        if (!pairResult.isSuccess) return pairResult

        XLog.i(TAG, "Local ADB discovering connect port")
        val connectDiscovery = LocalAdbDiscovery.discoverBlocking(appContext, timeoutMs = 8_000L)
        XLog.i(
            TAG,
            "Local ADB connect discovery host=${connectDiscovery.host} connect=${connectDiscovery.connectHost}:${connectDiscovery.connectPort}"
        )
        val connectPort = connectDiscovery.connectPort
            ?: firstDiscovery.connectPort
        if (connectPort != null) {
            val connectHost = connectDiscovery.connectHost
                ?: firstDiscovery.connectHost
                ?: connectDiscovery.host.ifBlank { firstDiscovery.host }
            return configureConnection(connectHost.ifBlank { DEFAULT_HOST }, connectPort)
        }

        return scanAndConfigureConnection(
            listOf(
                DEFAULT_HOST,
                connectDiscovery.connectHost,
                firstDiscovery.connectHost,
                connectDiscovery.host,
                firstDiscovery.host,
                pairHost,
            )
        ) ?: ShellCommandResult(-1, false, "", context.getString(R.string.local_adb_connect_port_missing))
    }

    @JvmStatic
    fun reconnectRemembered(context: Context, timeoutMs: Long = 5_000L): ShellCommandResult {
        if (!hasRememberedPairing()) {
            return ShellCommandResult(-1, false, "", context.getString(R.string.local_adb_pairing_required))
        }

        if (client?.connectionCheck() == true) {
            KVUtils.setLocalAdbPaired(true)
            markReady()
            return ShellCommandResult(0, false, READY_PROBE, "")
        }

        val discovery = LocalAdbDiscovery.discoverBlocking(context.applicationContext, timeoutMs)
        val connectPort = discovery.connectPort
        if (connectPort != null) {
            val connectHost = discovery.connectHost ?: discovery.host.ifBlank { DEFAULT_HOST }
            val result = configureConnection(connectHost, connectPort)
            if (result.isSuccess) {
                KVUtils.setLocalAdbPaired(true)
            }
            return result
        }

        val savedPort = KVUtils.getLocalAdbPort()
        if (savedPort > 0) {
            val result = configureConnection(KVUtils.getLocalAdbHost().ifBlank { DEFAULT_HOST }, savedPort)
            if (result.isSuccess) {
                KVUtils.setLocalAdbPaired(true)
            }
            return result
        }

        return ShellCommandResult(-1, false, "", context.getString(R.string.local_adb_connect_port_missing))
    }

    private fun scanAndConfigureConnection(hostCandidates: List<String?>): ShellCommandResult? {
        val hosts = hostCandidates
            .mapNotNull { it?.takeIf { host -> host.isNotBlank() } }
            .ifEmpty { listOf(DEFAULT_HOST) }
            .distinct()
        val previousHost = KVUtils.getLocalAdbHost()
        val previousPort = KVUtils.getLocalAdbPort()

        for (host in hosts) {
            val openPorts = scanOpenTcpPorts(host)
            XLog.i(TAG, "Local ADB scan found ${openPorts.size} open port(s) for host=$host")
            for (port in openPorts) {
                val result = configureConnection(host, port, logFailure = false)
                if (result.isSuccess) {
                    return result
                }
            }
        }

        KVUtils.setLocalAdbHost(previousHost)
        KVUtils.setLocalAdbPort(previousPort)
        resetClient()
        return null
    }

    private fun scanOpenTcpPorts(host: String): List<Int> {
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val nextPort = AtomicInteger(PORT_SCAN_MIN)
        val deadline = System.currentTimeMillis() + PORT_SCAN_TOTAL_TIMEOUT_MS
        val executor = Executors.newFixedThreadPool(PORT_SCAN_THREADS) { runnable ->
            Thread(runnable, "bqaagent-adb-port-scan").apply { isDaemon = true }
        }

        try {
            repeat(PORT_SCAN_THREADS) {
                executor.execute {
                    while (System.currentTimeMillis() < deadline && results.size < PORT_SCAN_MAX_RESULTS) {
                        val port = nextPort.getAndIncrement()
                        if (port > PORT_SCAN_MAX) return@execute
                        if (isTcpPortOpen(host, port)) {
                            results.add(port)
                        }
                    }
                }
            }
            executor.shutdown()
            executor.awaitTermination(PORT_SCAN_TOTAL_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            XLog.w(TAG, "Local ADB port scan failed for host=$host", e)
        } finally {
            executor.shutdownNow()
        }

        return results.toList().distinct().sorted()
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PORT_SCAN_CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    @JvmStatic
    fun parseSetupInput(raw: String): SetupRequest? {
        val tokens = raw
            .trim()
            .split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var host = DEFAULT_HOST
        var connectPort: Int? = null
        var cursor = 0

        val first = tokens[0]
        val hostPort = parseHostPort(first)
        if (hostPort != null) {
            host = hostPort.first
            connectPort = hostPort.second
            cursor = 1
        } else {
            val firstPort = first.toIntOrNull()
            if (firstPort != null) {
                connectPort = firstPort
                cursor = 1
            } else if (tokens.size >= 2) {
                host = first
                connectPort = tokens[1].toIntOrNull()
                cursor = 2
            }
        }

        val port = connectPort ?: return null
        if (port <= 0 || port > 65535) return null

        val pairPort = tokens.getOrNull(cursor)?.toIntOrNull()
        val pairingCode = tokens.getOrNull(cursor + 1)
        if (pairPort != null && (pairPort <= 0 || pairPort > 65535)) return null
        if (pairPort != null && pairingCode.isNullOrBlank()) return null

        return SetupRequest(
            host = host.ifBlank { DEFAULT_HOST },
            connectPort = port,
            pairPort = pairPort,
            pairingCode = pairingCode?.takeIf { it.isNotBlank() },
        )
    }

    @JvmStatic
    fun parsePairingInput(raw: String, defaultHost: String = DEFAULT_HOST): PairingInput? {
        val tokens = raw
            .trim()
            .split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var host = defaultHost.ifBlank { DEFAULT_HOST }
        var pairPort: Int? = null
        var pairingCode: String

        if (tokens.size == 1) {
            pairingCode = tokens[0]
            return PairingInput(host, null, pairingCode)
        }

        val first = tokens[0]
        val hostPort = parseHostPort(first)
        when {
            hostPort != null -> {
                if (tokens.size != 2) return null
                host = hostPort.first
                pairPort = hostPort.second
                pairingCode = tokens[1]
            }
            tokens.size == 2 -> {
                val maybePort = first.toIntOrNull()
                if (maybePort != null) {
                    pairPort = maybePort
                } else {
                    host = first
                }
                pairingCode = tokens[1]
            }
            tokens.size == 3 -> {
                host = first
                pairPort = tokens[1].toIntOrNull()
                pairingCode = tokens[2]
            }
            else -> return null
        }

        val code = pairingCode.takeIf { it.isNotBlank() } ?: return null
        if (pairPort != null && pairPort !in 1..65535) return null
        return PairingInput(host.ifBlank { DEFAULT_HOST }, pairPort, code)
    }

    @JvmStatic
    fun openWirelessDebuggingSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(ACTION_WIRELESS_DEBUGGING_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
            buildDeveloperOptionsHighlightIntent(),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT),
        )
        return intents.any { intent -> openSettingsIntent(context, intent) }
    }

    private fun buildDeveloperOptionsHighlightIntent(): Intent {
        val args = Bundle().apply {
            putString(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY)
        }
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args)
    }

    private fun openSettingsIntent(context: Context, intent: Intent): Boolean {
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            XLog.i(TAG, "Settings action is not supported on this device: ${intent.action}")
            return false
        }

        return runCatching { context.startActivity(intent) }
            .onFailure { XLog.w(TAG, "Failed to open settings action: ${intent.action}", it) }
            .isSuccess
    }

    private fun ensureClientLocked(timeoutMs: Long): Kadb? {
        val existing = client
        if (existing?.connectionCheck() == true) return existing

        val host = KVUtils.getLocalAdbHost().ifBlank { return null }
        val port = KVUtils.getLocalAdbPort()
        if (port <= 0) return null

        runCatching { existing?.close() }
        return Kadb.create(
            host = host,
            port = port,
            connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS,
            socketTimeout = timeoutMs.coerceAtLeast(DEFAULT_CONNECT_TIMEOUT_MS.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        ).also {
            client = it
        }
    }

    private fun ensureIdentityLocked() {
        val savedCert = KVUtils.getLocalAdbCert()
        val savedKey = KVUtils.getLocalAdbPrivateKey()
        if (savedCert != null && savedKey != null) {
            runCatching {
                KadbCert.set(savedCert, savedKey)
                return
            }.onFailure { XLog.w(TAG, "Saved Local ADB identity is invalid; regenerating", it) }
        }

        val (cert, key) = KadbCert.get(cn = "BQAAgent", ou = "BQAAgent", o = "BQAAgent")
        KVUtils.setLocalAdbCert(cert)
        KVUtils.setLocalAdbPrivateKey(key)
    }

    private fun resetClient() {
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
        }
    }

    private fun markReady() {
        lastReadyAt = System.currentTimeMillis()
    }

    private fun hasSavedIdentity(): Boolean {
        return KVUtils.getLocalAdbCert() != null && KVUtils.getLocalAdbPrivateKey() != null
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator >= value.lastIndex - 1) return null
        val host = value.substring(0, separator).trim().removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (port <= 0 || port > 65535) return null
        return host to port
    }

    private fun formatPairingError(error: Throwable): String {
        val message = error.message ?: error.toString()
        val cause = error.cause?.message?.takeIf { it.isNotBlank() }
        val prefix = error::class.java.simpleName.ifBlank { "Error" }
        return if (cause == null || cause == message) {
            "$prefix: $message"
        } else {
            "$prefix: $message; cause=$cause"
        }
    }
}
