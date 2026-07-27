// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.adb

data class ShellCommandResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0

    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }

    companion object {
        private const val EXIT_PREFIX = "exit="
        private const val TIMEOUT_PREFIX = "timeout="
        private const val STDOUT_MARKER = "\n<<<BQAAGENT_STDOUT>>>\n"
        private const val STDERR_MARKER = "\n<<<BQAAGENT_STDERR>>>\n"

        @JvmStatic
        fun encode(exitCode: Int, timedOut: Boolean, stdout: String, stderr: String): String {
            return buildString {
                append(EXIT_PREFIX).append(exitCode).append('\n')
                append(TIMEOUT_PREFIX).append(timedOut).append(STDOUT_MARKER)
                append(stdout)
                append(STDERR_MARKER)
                append(stderr)
            }
        }

        @JvmStatic
        fun parse(raw: String): ShellCommandResult {
            val stdoutIndex = raw.indexOf(STDOUT_MARKER)
            val stderrIndex = raw.indexOf(STDERR_MARKER)
            if (stdoutIndex < 0 || stderrIndex < stdoutIndex) {
                return ShellCommandResult(-1, false, "", raw)
            }

            val header = raw.substring(0, stdoutIndex).lineSequence().toList()
            val exitCode = header.firstOrNull { it.startsWith(EXIT_PREFIX) }
                ?.removePrefix(EXIT_PREFIX)
                ?.toIntOrNull()
                ?: -1
            val timedOut = header.firstOrNull { it.startsWith(TIMEOUT_PREFIX) }
                ?.removePrefix(TIMEOUT_PREFIX)
                ?.toBooleanStrictOrNull()
                ?: false
            val stdout = raw.substring(stdoutIndex + STDOUT_MARKER.length, stderrIndex)
            val stderr = raw.substring(stderrIndex + STDERR_MARKER.length)
            return ShellCommandResult(exitCode, timedOut, stdout, stderr)
        }
    }
}
