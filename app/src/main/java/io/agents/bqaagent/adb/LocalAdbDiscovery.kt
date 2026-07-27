// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.agents.bqaagent.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object LocalAdbDiscovery {
    private const val TAG = "LocalAdbDiscovery"
    private const val LOCAL_HOST = "127.0.0.1"
    private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."

    data class Endpoint(
        val host: String,
        val port: Int,
    )

    data class Result(
        val host: String = LOCAL_HOST,
        val connectHost: String? = null,
        val connectPort: Int? = null,
        val pairHost: String? = null,
        val pairPort: Int? = null,
    ) {
        fun toSetupPreset(fallback: String): String {
            val savedRequest = LocalAdbAutomation.parseSetupInput(fallback)
            val setupHost = connectHost ?: pairHost ?: host
            return when {
                connectPort != null && pairPort != null -> "$setupHost:$connectPort $pairPort "
                connectPort != null -> "$setupHost:$connectPort"
                pairPort != null && savedRequest != null -> "${savedRequest.host}:${savedRequest.connectPort} $pairPort "
                else -> fallback
            }
        }
    }

    @JvmStatic
    fun discoverBlocking(context: Context, timeoutMs: Long = 3_500L): Result {
        val appContext = context.applicationContext
        val executor = Executors.newFixedThreadPool(2)
        val connectFuture = executor.submit<Endpoint?> {
            discoverEndpoint(appContext, CONNECT_SERVICE_TYPE, timeoutMs)
        }
        val pairingFuture = executor.submit<Endpoint?> {
            discoverEndpoint(appContext, PAIRING_SERVICE_TYPE, timeoutMs)
        }

        return try {
            val connectEndpoint = runCatching { connectFuture.get(timeoutMs + 500L, TimeUnit.MILLISECONDS) }.getOrNull()
            val pairEndpoint = runCatching { pairingFuture.get(timeoutMs + 500L, TimeUnit.MILLISECONDS) }.getOrNull()
            Result(
                host = connectEndpoint?.host ?: pairEndpoint?.host ?: LOCAL_HOST,
                connectHost = connectEndpoint?.host,
                connectPort = connectEndpoint?.port,
                pairHost = pairEndpoint?.host,
                pairPort = pairEndpoint?.port,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun discoverEndpoint(context: Context, serviceType: String, timeoutMs: Long): Endpoint? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val multicastLock = acquireMulticastLock(context)
        val discoveredEndpoint = AtomicReference<Endpoint?>(null)
        val finished = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        val discoveryStarted = AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.set(false)
                XLog.w(TAG, "Resolve failed for $serviceType: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving.set(false)
                val port = serviceInfo.port
                if (port in 1..65535) {
                    val host = serviceInfo.host?.hostAddress?.takeIf { it.isNotBlank() } ?: LOCAL_HOST
                    discoveredEndpoint.set(Endpoint(host, port))
                    finished.countDown()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                discoveryStarted.set(true)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                XLog.w(TAG, "Discovery start failed for $serviceType: $errorCode")
                finished.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                XLog.w(TAG, "Discovery stop failed for $serviceType: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.equals(serviceType, ignoreCase = true)) return
                if (resolving.compareAndSet(false, true)) {
                    @Suppress("DEPRECATION")
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                        .onFailure {
                            resolving.set(false)
                            XLog.w(TAG, "Resolve request failed for $serviceType", it)
                        }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        return try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            finished.await(timeoutMs, TimeUnit.MILLISECONDS)
            discoveredEndpoint.get()
        } catch (e: Throwable) {
            XLog.w(TAG, "Discovery failed for $serviceType", e)
            null
        } finally {
            if (discoveryStarted.get()) {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
            runCatching { multicastLock?.release() }
        }
    }

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching {
            wifiManager.createMulticastLock("bqaagent-local-adb-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            XLog.w(TAG, "Failed to acquire multicast lock", it)
        }.getOrNull()
    }
}
