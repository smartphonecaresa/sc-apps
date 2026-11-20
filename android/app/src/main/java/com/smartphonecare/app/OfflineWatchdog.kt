package com.smartphonecare.app

import android.content.Context
import androidx.work.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Offline failsafe. Runs every ~15 minutes (the shortest interval
 * WorkManager allows for periodic work) and does a tiny real-connectivity
 * check — not just "is Wi-Fi on", but "did a request actually complete" —
 * against Google's standard no-content probe. On success it stamps
 * "lastOnlineAt" and clears any auto-lock it placed. On failure it checks
 * how long it has been since the last success; past the configured
 * threshold (default 72h, overridable from Firestore — see
 * SCBridge.setOfflineLockHours, cached here because the device is by
 * definition offline when this fires) it shows the same lock screen the
 * shop's own "Lock device" button shows, using the customer/shop details
 * that were cached the last time the web app synced (SCBridge.cacheLockInfo).
 *
 * This never talks to Firestore directly — it only decides *whether* to
 * show the existing lock screen. The real payment status is always
 * re-checked by the web app's own finSync() the moment connectivity
 * returns (already wired since v2987 — the 'online' browser event there
 * re-polls Firestore immediately), which then calls deviceLockClear() if
 * the device is actually paid up.
 */
object OfflineWatchdog {
    private const val WORK_NAME = "sc_offline_watchdog"
    private const val PREFS = "sc_watchdog"
    private const val K_LAST_ONLINE = "lastOnlineAt"
    private const val K_HOURS = "offlineLockHours"
    private const val K_AUTO_LOCKED = "autoLocked"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
        )
        // seed lastOnlineAt so a fresh enrolment doesn't start the clock at epoch 0
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(K_LAST_ONLINE)) p.edit().putLong(K_LAST_ONLINE, System.currentTimeMillis()).apply()
    }

    fun setOfflineLockHours(ctx: Context, hours: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(K_HOURS, hours).apply()
    }

    fun markOnlineNow(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong(K_LAST_ONLINE, System.currentTimeMillis()).apply()
        if (p.getBoolean(K_AUTO_LOCKED, false)) {
            // Connectivity is back; leave the actual unlock decision to the
            // web app's finSync (it will call deviceLockClear() if paid).
            // We just stop treating this as "our" auto-lock going forward.
            p.edit().putBoolean(K_AUTO_LOCKED, false).apply()
        }
    }

    class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val ok = probe()
            val p = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (ok) {
                markOnlineNow(applicationContext)
                return Result.success()
            }
            val lastOnline = p.getLong(K_LAST_ONLINE, System.currentTimeMillis())
            val hours = p.getInt(K_HOURS, 72)
            val elapsedH = (System.currentTimeMillis() - lastOnline) / 3_600_000.0
            if (elapsedH >= hours && SCDeviceAdminReceiver.isDeviceOwner(applicationContext)) {
                val cache = applicationContext.getSharedPreferences("sc_lock_cache", Context.MODE_PRIVATE)
                val i = android.content.Intent(applicationContext, LockActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("message", "No internet for over $hours hours. The shop must verify your installment is paid before this unlocks.")
                    .putExtra("amount", cache.getString("amount", "") ?: "")
                    .putExtra("shopName", cache.getString("shopName", "") ?: "")
                    .putExtra("custName", cache.getString("custName", "") ?: "")
                    .putExtra("dueDate", cache.getString("dueDate", "") ?: "")
                    .putExtra("shopPhone", cache.getString("shopPhone", "") ?: "")
                    .putExtra("offline", true)
                applicationContext.startActivity(i)
                p.edit().putBoolean(K_AUTO_LOCKED, true).apply()
            }
            return Result.success()
        }

        private fun probe(): Boolean = try {
            val url = URL("https://www.gstatic.com/generate_204")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            code == 204 || code == 200
        } catch (e: Exception) { false }
    }
}
