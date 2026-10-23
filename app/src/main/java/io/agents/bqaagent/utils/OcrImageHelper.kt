// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Shared helper for OCR image acquisition.
 *
 * Validates, copies to the app-private ocr_uploads directory, and returns the file path.
 * Reuses the same validation constraints as the existing screenshot-fallback image picker
 * in ComposeChatActivity, but stores files in a separate directory to avoid interference.
 */
object OcrImageHelper {

    private const val TAG = "OcrImageHelper"
    private const val MAX_FILE_SIZE = 20L * 1024 * 1024
    private const val MIN_FILE_SIZE = 10L * 1024

    sealed class OcrImageError(val message: String) {
        class InvalidType : OcrImageError("Not an image file")
        class TooLarge(val sizeMb: Long) : OcrImageError("Image too large: ${sizeMb}MB")
        class TooSmall : OcrImageError("Image too small")
        class StorageUnavailable : OcrImageError("Storage unavailable")
        class CopyFailed : OcrImageError("Failed to save image")
    }

    /**
     * Validate the image URI and copy it to the ocr_uploads directory.
     *
     * @return Result with the absolute file path on success, or [OcrImageError] on failure
     */
    fun prepareImage(uri: Uri, context: Context): Result<String> {
        return try {
            val resolver = context.contentResolver

            val mimeType = if (uri.scheme == "file") {
                val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
                when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> null
                }
            } else {
                resolver.getType(uri)
            }
            if (mimeType == null || !mimeType.startsWith("image/")) {
                return Result.failure(IllegalArgumentException(OcrImageError.InvalidType().message))
            }

            val fileSize = if (uri.scheme == "file") {
                File(uri.path ?: "").let { if (it.exists()) it.length() else 0L }
            } else {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
            if (fileSize > MAX_FILE_SIZE) {
                return Result.failure(IllegalArgumentException(
                    OcrImageError.TooLarge(fileSize / (1024 * 1024)).message))
            }
            if (fileSize < MIN_FILE_SIZE) {
                return Result.failure(IllegalArgumentException(OcrImageError.TooSmall().message))
            }

            val uploadDir = context.getExternalFilesDir("ocr_uploads")
                ?: return Result.failure(IllegalStateException(OcrImageError.StorageUnavailable().message))
            if (!uploadDir.exists()) uploadDir.mkdirs()

            val extension = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("png") -> "png"
                else -> "png"
            }
            val destFile = File(uploadDir, "ocr_${System.currentTimeMillis()}.$extension")

            if (uri.scheme == "file") {
                val srcFile = File(uri.path ?: "")
                if (!srcFile.exists() || !srcFile.canRead()) {
                    return Result.failure(IllegalStateException(OcrImageError.CopyFailed().message))
                }
                srcFile.copyTo(destFile, overwrite = true)
            } else {
                resolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return Result.failure(IllegalStateException(OcrImageError.CopyFailed().message))
            }

            XLog.i(TAG, "OCR image saved to ${destFile.absolutePath} (${fileSize / 1024}KB)")
            Result.success(destFile.absolutePath)
        } catch (e: Exception) {
            XLog.e(TAG, "prepareImage failed", e)
            Result.failure(e)
        }
    }

    /**
     * Clean up old OCR images to prevent storage accumulation.
     * Keeps the most recent [keepCount] files.
     */
    fun cleanupOldImages(context: Context, keepCount: Int = 10) {
        try {
            val dir = context.getExternalFilesDir("ocr_uploads") ?: return
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= keepCount) return
            files.sortedByDescending { it.lastModified() }
                .drop(keepCount)
                .forEach { it.delete() }
        } catch (e: Exception) {
            XLog.w(TAG, "cleanupOldImages failed: ${e.message}")
        }
    }
}