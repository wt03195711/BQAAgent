// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

import android.content.Context

object LocalAdbHostResolver {
    private const val LOOPBACK_HOST = "127.0.0.1"

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun defaultPairingHost(context: Context): String {
        return LOOPBACK_HOST
    }
}
