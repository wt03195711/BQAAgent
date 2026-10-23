// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.utils

import android.content.Context
import android.net.Uri
import android.widget.Toast
import io.agents.bqaagent.R
import java.io.File

/**
 * Shared picker->file helper for the user-assisted screenshot fallback (analyze_screen_visual
 * returning SCREENSHOT_BLOCKED / SCREENSHOT_FAILED). Both ComposeChatActivity (chat) and
 * BatchActivity (batch) host an image picker; centralizing the validation, size limits, save
 * location and user toasts here keeps the two modes byte-for-byte identical.
 *
 * Returns the absolute path of the saved image, or null when the pick was cancelled or invalid.
 * Callers feed the result straight into provideUserImage(path?) — null means "no image" (skip),
 * which the agent treats exactly like a timeout.
 */
object UserImageUploadHelper {

    private const val TAG = "UserImageUploadHelper"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MIN_BYTES = 10L * 1024

    fun savePickedImage(context: Context, uri: Uri?): String? {
        if (uri == null) return null
        var destFile: File? = null
        return try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)
            if (mimeType == null || !mimeType.startsWith("image/")) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_image_invalid_type),
                    Toast.LENGTH_LONG
                ).show()
                return null
            }

            val fileSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            if (fileSize > MAX_BYTES) {
                val sizeMb = fileSize / (1024 * 1024)
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_image_too_large, sizeMb),
                    Toast.LENGTH_LONG
                ).show()
                return null
            }
            if (fileSize < MIN_BYTES) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_image_too_small),
                    Toast.LENGTH_LONG
                ).show()
                return null
            }

            val uploadDir = context.getExternalFilesDir("user_uploads") ?: return null
            if (!uploadDir.exists()) uploadDir.mkdirs()
            val extension = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("png") -> "png"
                else -> "png"
            }
            destFile = File(uploadDir, "screenshot_${System.currentTimeMillis()}.$extension")
            resolver.openInputStream(uri)?.use { input ->
                destFile!!.outputStream().use { output -> input.copyTo(output) }
            }
            XLog.i(
                TAG,
                "User image saved to ${destFile!!.absolutePath} (${fileSize / 1024}KB, $mimeType)"
            )
            destFile!!.absolutePath
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save user image", e)
            destFile?.delete()
            null
        }
    }
}