// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.channel

import io.agents.bqaagent.utils.XLog

enum class Channel(val displayName: String) {
    LOCAL("Local"),
}

object ChannelManager {

    private const val TAG = "ChannelManager"

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) {
            XLog.w(TAG, "sendMessage skipping empty message [${channel.displayName}]")
            return
        }
        XLog.d(TAG, "sendMessage [${channel.displayName}/$messageID]: ${trimmedContent.take(120)}")
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        XLog.d(TAG, "sendImage [${channel.displayName}/$messageID]: ${imageBytes.size} bytes")
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        XLog.i(TAG, "sendFile: ${file.name} via ${channel.displayName}")
    }

    @JvmStatic
    fun flushMessages(channel: Channel) {
        XLog.d(TAG, "flushMessages [${channel.displayName}]")
    }
}
