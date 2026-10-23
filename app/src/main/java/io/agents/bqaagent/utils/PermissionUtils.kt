package io.agents.bqaagent.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {
    // All original helper methods are retained below.

    /** Check the microphone recording permission. */
    fun hasRecordAudio(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Request the recording permission. */
    fun requestRecordAudio(activity: androidx.appcompat.app.AppCompatActivity, requestCode: Int = 1002) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            requestCode
        )
    }
}