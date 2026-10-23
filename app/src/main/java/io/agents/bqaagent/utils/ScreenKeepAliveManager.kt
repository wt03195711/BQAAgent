package io.agents.bqaagent.utils

import android.content.Context
import android.os.PowerManager
import io.agents.bqaagent.adb.LocalAdbAutomation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen keep-alive manager.
 *
 * Two independent channels:
 *
 *  1. PARTIAL_WAKE_LOCK — keeps CPU running. Cheap, always safe to hold.
 *  2. System `screen_off_timeout` override via ADB shell — the ONLY reliable way to
 *     prevent the screen from turning off while a third-party target App is in the
 *     foreground (all in-app mechanisms such as SCREEN_BRIGHT_WAKE_LOCK,
 *     FLAG_KEEP_SCREEN_ON and KeyguardLock are ignored by modern Android / OEM ROMs
 *     once our Activity is backgrounded).
 *
 * The ADB-based channel is task-scoped and reference-counted: only the outermost
 * acquire()/release() pair actually writes/restores the system setting. The
 * original value is persisted to MMKV so a crashed process can be recovered by
 * [restoreIfCrashed] on next launch.
 */
object ScreenKeepAliveManager {

    private const val TAG = "ScreenKeepAlive"
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

    // ~24.8 days — effectively "never turn off" for the duration of any task.
    private const val KEEP_AWAKE_TIMEOUT_MS = Int.MAX_VALUE
    // Safe fallback if reading the current value fails.
    private const val DEFAULT_ORIGINAL_TIMEOUT_MS = 60_000

    private const val KEY_SAVED_SCREEN_OFF_TIMEOUT = "KEY_SAVED_SCREEN_OFF_TIMEOUT"
    private const val KEY_TIMEOUT_OVERRIDE_ACTIVE = "KEY_TIMEOUT_OVERRIDE_ACTIVE"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ScreenKeepAlive-IO").apply { isDaemon = true }
    }

    private val refCount = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null
    /**
     * True once [acquireCpuOnly] has been called (app-scoped hold that must survive
     * every task-scoped release()). Prevents releasePartialWakeLockLocked() from
     * dropping the CPU wake lock that AppViewModel.afterInit() installed for the
     * whole process lifetime.
     */
    private var cpuOnlyHeld = false

    // ==================== Public API ====================

    /**
     * Task-scoped acquire: bumps the ref count and, on the outermost call, overrides
     * the system screen_off_timeout via ADB shell. Safe to call from any thread; the
     * ADB work is dispatched to a background executor.
     */
    @Synchronized
    fun acquire(context: Context) {
        appContext = context.applicationContext
        acquirePartialWakeLockLocked()
        val count = refCount.incrementAndGet()
        XLog.d(TAG, "acquire() refCount=$count")
        if (count == 1) {
            ioExecutor.execute { applyScreenTimeoutOverride() }
        }
    }

    /**
     * Task-scoped release. Only the outermost release actually restores the
     * original screen_off_timeout.
     */
    @Synchronized
    fun release() {
        val count = refCount.decrementAndGet()
        if (count < 0) {
            refCount.set(0)
            XLog.w(TAG, "release() called more times than acquire(); clamping to 0")
            return
        }
        XLog.d(TAG, "release() refCount=$count")
        if (count == 0) {
            ioExecutor.execute { restoreScreenTimeout() }
            releasePartialWakeLockLocked()
        }
    }

    /**
     * Application-scoped acquire: holds PARTIAL_WAKE_LOCK only (CPU stays awake),
     * WITHOUT touching the system screen_off_timeout. Use this for long-lived
     * app-level keep-alive where blocking screen-off would be intrusive.
     */
    @Synchronized
    fun acquireCpuOnly(context: Context) {
        appContext = context.applicationContext
        acquirePartialWakeLockLocked()
        cpuOnlyHeld = true
        XLog.i(TAG, "acquireCpuOnly() — PARTIAL_WAKE_LOCK held (persistent)")
    }

    /**
     * Refresh the PARTIAL_WAKE_LOCK timeout without touching refCount or the
     * system screen_off_timeout override. Call this periodically from long-running
     * tasks (e.g. every 4 minutes) to prevent the 10-minute wake-lock timeout from
     * expiring mid-task. Safe to call before any acquire() — it simply no-ops.
     */
    @Synchronized
    fun renew() {
        if (wakeLock == null) return
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            XLog.d(TAG, "PARTIAL_WAKE_LOCK renewed (timeout=${WAKE_LOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            XLog.w(TAG, "renew failed: ${e.message}")
        }
    }

    /**
     * Called from Application.onCreate on a background thread. If the previous
     * process crashed while a timeout override was active, restore the user's
     * original screen_off_timeout so we don't leave the device permanently awake.
     */
    fun restoreIfCrashed() {
        ioExecutor.execute {
            try {
                val active = KVUtils.getBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, false)
                if (!active) return@execute
                XLog.w(TAG, "Detected stale screen_off_timeout override from previous run; restoring")
                restoreScreenTimeout()
            } catch (e: Exception) {
                XLog.w(TAG, "restoreIfCrashed failed: ${e.message}")
            }
        }
    }

    // ==================== Internals ====================

    private fun acquirePartialWakeLockLocked() {
        try {
            if (wakeLock?.isHeld == true) return
            val ctx = appContext ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BQAAgent::KeepAlive")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
            XLog.i(TAG, "PARTIAL_WAKE_LOCK acquired (timeout=${WAKE_LOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to acquire PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun releasePartialWakeLockLocked() {
        // If acquireCpuOnly() is holding the lock for the whole process lifetime,
        // do NOT release it here — task-scoped release() must not disturb it.
        if (cpuOnlyHeld) {
            XLog.d(TAG, "releasePartialWakeLockLocked skipped (cpuOnlyHeld=true)")
            return
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            XLog.i(TAG, "PARTIAL_WAKE_LOCK released")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to release PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    /** Runs on IO executor. Reads and saves the original value, then applies override. */
    private fun applyScreenTimeoutOverride() {
        try {
            if (!LocalAdbAutomation.hasConnectionConfig()) {
                XLog.w(TAG, "ADB not configured — cannot override screen_off_timeout; " +
                        "falling back to PARTIAL_WAKE_LOCK only")
                return
            }
            val alreadyActive = KVUtils.getBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, false)
            val originalValue: Int = if (alreadyActive) {
                KVUtils.getInt(KEY_SAVED_SCREEN_OFF_TIMEOUT, DEFAULT_ORIGINAL_TIMEOUT_MS)
            } else {
                val read = readCurrentScreenOffTimeout()
                if (read <= 0) {
                    XLog.w(TAG, "Could not read current screen_off_timeout; aborting override " +
                            "to avoid corrupting user setting")
                    return
                }
                read
            }

            // Persist intent BEFORE the ADB write. If the process is killed between
            // the write and the KV commit, restoreIfCrashed() on next launch would
            // miss the override and leave the device permanently awake. Writing KV
            // first is safe: if the subsequent ADB write fails we simply clear it.
            if (!alreadyActive) {
                KVUtils.putInt(KEY_SAVED_SCREEN_OFF_TIMEOUT, originalValue)
                KVUtils.putBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, true)
                KVUtils.sync()
                XLog.i(TAG, "Saved original screen_off_timeout=${originalValue}ms (pre-write)")
            }

            val writeResult = LocalAdbAutomation.exec(
                "settings put system screen_off_timeout $KEEP_AWAKE_TIMEOUT_MS",
                5_000L
            )
            if (!writeResult.isSuccess) {
                XLog.w(TAG, "Failed to override screen_off_timeout: exit=${writeResult.exitCode} " +
                        "err=${writeResult.stderr}; rolling back KV")
                if (!alreadyActive) {
                    KVUtils.putBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, false)
                    KVUtils.remove(KEY_SAVED_SCREEN_OFF_TIMEOUT)
                    KVUtils.sync()
                }
                return
            }
            XLog.i(TAG, "screen_off_timeout overridden to $KEEP_AWAKE_TIMEOUT_MS ms")
        } catch (e: Exception) {
            XLog.w(TAG, "applyScreenTimeoutOverride failed: ${e.message}")
        }
    }

    /** Runs on IO executor. Restores the user's original screen_off_timeout. */
    private fun restoreScreenTimeout() {
        try {
            if (!KVUtils.getBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, false)) {
                return
            }
            val saved = KVUtils.getInt(KEY_SAVED_SCREEN_OFF_TIMEOUT, DEFAULT_ORIGINAL_TIMEOUT_MS)
            val safeValue = if (saved in 15_000..60 * 60 * 1000) saved else DEFAULT_ORIGINAL_TIMEOUT_MS

            if (LocalAdbAutomation.hasConnectionConfig()) {
                val r1 = LocalAdbAutomation.exec(
                    "settings put system screen_off_timeout $safeValue",
                    5_000L
                )
                if (r1.isSuccess) {
                    XLog.i(TAG, "screen_off_timeout restored to ${safeValue}ms")
                } else {
                    XLog.w(TAG, "Failed to restore screen_off_timeout: ${r1.stderr}")
                }
            } else {
                XLog.w(TAG, "ADB not configured — cannot restore screen_off_timeout; " +
                        "clearing persisted state anyway")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "restoreScreenTimeout failed: ${e.message}")
        } finally {
            KVUtils.putBoolean(KEY_TIMEOUT_OVERRIDE_ACTIVE, false)
            KVUtils.remove(KEY_SAVED_SCREEN_OFF_TIMEOUT)
            KVUtils.sync()
        }
    }

    /**
     * Returns the current system screen_off_timeout in milliseconds, or a value <= 0
     * when it cannot be determined (ADB not answering, unparseable output, or an
     * out-of-range value). Callers MUST check for <= 0 and abort rather than
     * substituting a default — writing back a wrong "original" value would
     * silently change the user's real setting on restore.
     */
    private fun readCurrentScreenOffTimeout(): Int {
        return try {
            val result = LocalAdbAutomation.exec("settings get system screen_off_timeout", 5_000L)
            if (!result.isSuccess) {
                XLog.w(TAG, "settings get failed: ${result.stderr}")
                return -1
            }
            val parsed = result.stdout.trim().toIntOrNull()
            if (parsed == null || parsed <= 0 || parsed >= KEEP_AWAKE_TIMEOUT_MS) {
                XLog.w(TAG, "settings get returned unusable value: '${result.stdout.trim()}'")
                return -1
            }
            parsed
        } catch (e: Exception) {
            XLog.w(TAG, "readCurrentScreenOffTimeout failed: ${e.message}")
            -1
        }
    }
}