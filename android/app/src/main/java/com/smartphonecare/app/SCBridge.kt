package com.smartphonecare.app

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Exposed to the web app as `window.SCNative`. Method names match exactly
 * what track-app/index.html already calls (see finSync / inResellerApp /
 * appLockSync in the web code) — nothing on the web side needs to change
 * except adding a small `window.SCInbound(type, json)` receiver for the
 * two calls that report data back (location, photo, battery/version).
 */
class SCBridge(private val activity: MainActivity, private val webView: WebView) {

    init { SCBridgeHolder.current = this }

    private val ctx: Context get() = activity.applicationContext
    private val dpm get() = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin get() = SCDeviceAdminReceiver.componentName(ctx)
    private var ringPlayer: MediaPlayer? = null

    // ---- identity -----------------------------------------------------

    @JavascriptInterface
    fun app(): String = BuildConfig.ROLE // "owner" for the shop's own phone, "reseller" for a financed phone

    @JavascriptInterface
    fun getVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun versionName(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun apkVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun setAuth(json: String) {
        ctx.getSharedPreferences("sc_auth", Context.MODE_PRIVATE).edit()
            .putString("cfg", json).apply()
    }

    @JavascriptInterface
    fun isDeviceOwner(): Boolean = SCDeviceAdminReceiver.isDeviceOwner(ctx)

    // ---- uninstall / app block (Device Owner only) --------------------

    @JavascriptInterface
    fun lockUninstall() {
        if (!isDeviceOwner()) return
        try { dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS) } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun unlockUninstall() {
        if (!isDeviceOwner()) return
        try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS) } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun blockApps(pkgCsv: String) {
        if (!isDeviceOwner()) return
        val pkgs = pkgCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        try {
            for (p in pkgs) dpm.setApplicationHidden(admin, p, true)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun unblockApps(pkgCsv: String) {
        if (!isDeviceOwner()) return
        val pkgs = pkgCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        try {
            for (p in pkgs) dpm.setApplicationHidden(admin, p, false)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun listUserApps(): String {
        val pm = ctx.packageManager
        val arr = org.json.JSONArray()
        try {
            for (info in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                if (info.packageName == ctx.packageName) continue
                val o = JSONObject()
                o.put("pkg", info.packageName)
                o.put("name", pm.getApplicationLabel(info).toString())
                arr.put(o)
            }
        } catch (e: Exception) {}
        return arr.toString()
    }

    // ---- block factory reset (Device Owner only) -----------------------

    @JavascriptInterface
    fun blockFactoryReset(on: Boolean) {
        if (!isDeviceOwner()) return
        try { dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) {}
        if (!on) try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) {}
    }

    // ---- screen lock ("pay to unlock") ---------------------------------

    @JavascriptInterface
    fun deviceLock(message: String, amount: String) {
        val i = Intent(ctx, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("message", message)
            .putExtra("amount", amount)
        ctx.startActivity(i)
    }

    // Richer lock screen: shop name, customer name, due date and the
    // shop's phone number (for the "Call shop" button), on top of the
    // existing message/amount. json = {"message","amount","shopName",
    // "custName","dueDate","shopPhone"}. Also caches every field locally
    // so the OfflineWatchdog can render the same screen with no network.
    @JavascriptInterface
    fun deviceLockRich(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        cacheLockInfo(json)
        val i = Intent(ctx, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("message", o.optString("message", "Please pay your installment to unlock."))
            .putExtra("amount", o.optString("amount", ""))
            .putExtra("shopName", o.optString("shopName", ""))
            .putExtra("custName", o.optString("custName", ""))
            .putExtra("dueDate", o.optString("dueDate", ""))
            .putExtra("shopPhone", o.optString("shopPhone", ""))
        ctx.startActivity(i)
    }

    @JavascriptInterface
    fun deviceLockClear() {
        LockActivity.dismiss()
    }

    // Lightweight, non-blocking overlay — does NOT start lock task, the
    // phone stays fully usable. For "Send payment reminder".
    @JavascriptInterface
    fun showReminder(message: String) {
        activity.runOnUiThread {
            try {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
        }
        // A toast disappears if the app isn't frontmost, so also fire a
        // normal notification the customer can see from anywhere.
        try {
            val chId = "sc_reminder"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(chId, "Payment reminders", android.app.NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val n = androidx.core.app.NotificationCompat.Builder(ctx, chId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Installment reminder")
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(9001, n)
        } catch (e: Exception) {}
    }

    // Keeps the most recent lock-screen context on disk so it can still be
    // shown correctly if the phone is fully offline when it auto-locks.
    @JavascriptInterface
    fun cacheLockInfo(json: String) {
        try {
            val o = JSONObject(json)
            ctx.getSharedPreferences("sc_lock_cache", Context.MODE_PRIVATE).edit()
                .putString("amount", o.optString("amount", ""))
                .putString("shopName", o.optString("shopName", ""))
                .putString("custName", o.optString("custName", ""))
                .putString("dueDate", o.optString("dueDate", ""))
                .putString("shopPhone", o.optString("shopPhone", ""))
                .apply()
        } catch (e: Exception) {}
    }

    // Overridable from Firestore (devices/{imei}.offlineLockHours); cached
    // locally because the device is offline exactly when this matters.
    @JavascriptInterface
    fun setOfflineLockHours(hours: Int) {
        try { OfflineWatchdog.setOfflineLockHours(ctx, hours) } catch (e: Exception) {}
    }

    // Marks "seen online" from the web side too (any successful Firestore
    // round trip counts, not just the watchdog's own probe).
    @JavascriptInterface
    fun markOnline() {
        try { OfflineWatchdog.markOnlineNow(ctx) } catch (e: Exception) {}
    }

    // ---- release (EMI fully paid off) ----------------------------------

    // Clears every Device-Owner restriction this app ever set and, where
    // the OS allows it, drops Device-Owner status itself so the phone is
    // fully back to normal. This does NOT remove the APK — Android gives
    // no Device-Owner API to force-uninstall the app that currently *is*
    // the device owner; the customer (or shop) still taps Uninstall once,
    // same as any other app, after this call finishes.
    @JavascriptInterface
    fun releaseDevice() {
        if (!isDeviceOwner()) return
        try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS) } catch (e: Exception) {}
        try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) {}
        try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT) } catch (e: Exception) {}
        try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER) } catch (e: Exception) {}
        try {
            for (info in ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
                try { dpm.setApplicationHidden(admin, info.packageName, false) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        try { LocationReportService.stop(ctx) } catch (e: Exception) {}
        try { LockActivity.dismiss() } catch (e: Exception) {}
        try { dpm.clearDeviceOwnerApp(ctx.packageName) } catch (e: Exception) {}
    }

    // ---- ring ------------------------------------------------------------

    @JavascriptInterface
    fun ring() {
        try {
            ringPlayer?.release()
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            ringPlayer = mp
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))
            }
            android.os.Handler(ctx.mainLooper).postDelayed({ stopRing() }, 30000)
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun stopRing() {
        try { ringPlayer?.stop(); ringPlayer?.release(); ringPlayer = null } catch (e: Exception) {}
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.cancel()
        } catch (e: Exception) {}
    }

    // ---- location --------------------------------------------------------

    @JavascriptInterface
    fun locStart() {
        val has = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!has) return
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        try {
            fused.getCurrentLocation(req, null).addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener
                val o = JSONObject()
                o.put("lat", loc.latitude)
                o.put("lng", loc.longitude)
                o.put("acc", loc.accuracy)
                o.put("at", System.currentTimeMillis())
                sendToWeb("loc", o.toString())
            }
        } catch (e: SecurityException) {}
        // Also start the background service so a fix keeps being reported
        // every few minutes while locAsk stays on, even if the app/WebView
        // isn't in the foreground.
        try { LocationReportService.start(ctx) } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun locStop() {
        try { LocationReportService.stop(ctx) } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun batteryPct(): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // ---- photo -------------------------------------------------------------

    @JavascriptInterface
    fun takePhoto() {
        try {
            PhotoCapture.capture(activity) { bytes ->
                if (bytes == null) return@capture
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val o = JSONObject()
                o.put("photoBase64", b64)
                o.put("at", System.currentTimeMillis())
                sendToWeb("photo", o.toString())
            }
        } catch (e: Exception) {}
    }

    // ---- wipe (Device Owner only, destructive, confirmed on the web side first) --

    @JavascriptInterface
    fun wipeDevice() {
        if (!isDeviceOwner()) return
        try { dpm.wipeData(0) } catch (e: Exception) {}
    }

    // ---- helper: deliver data back to the page that's already loaded ------

    fun sendToWeb(type: String, json: String) {
        activity.runOnUiThread {
            val js = "window.SCInbound && window.SCInbound(${JSONObject.quote(type)}, ${JSONObject.quote(json)});"
            webView.evaluateJavascript(js, null)
        }
    }

    fun onDestroy() {
        try { ringPlayer?.release() } catch (e: Exception) {}
        if (SCBridgeHolder.current === this) SCBridgeHolder.current = null
    }
}
