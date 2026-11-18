package com.smartphonecare.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

/**
 * Keeps reporting a fresh location fix every few minutes while the shop has
 * "Share location" turned on. Started by SCBridge.locStart(), stopped by
 * SCBridge.locStop(). Reports go straight back into the already-loaded
 * WebView page via SCBridge.sendToWeb, which the web app then writes to
 * Firestore (loc/{cid}) exactly like it already does for everything else —
 * this service does not talk to Firestore directly.
 */
class LocationReportService : Service() {

    companion object {
        private const val CHANNEL_ID = "sc_location"
        private const val NOTIF_ID = 42
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationReportService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationReportService::class.java))
        }
    }

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location sharing is on")
            .setContentText("The shop turned this on for this financed device.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { stopSelf(); return START_NOT_STICKY }

        if (client == null) {
            client = LocationServices.getFusedLocationProviderClient(this)
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS).build()
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val o = org.json.JSONObject()
                    o.put("lat", loc.latitude)
                    o.put("lng", loc.longitude)
                    o.put("acc", loc.accuracy)
                    o.put("at", System.currentTimeMillis())
                    // broadcast to whichever Activity currently holds the bridge
                    SCBridgeHolder.current?.sendToWeb("loc", o.toString())
                }
            }
            try { client!!.requestLocationUpdates(req, callback!!, mainLooper) } catch (e: SecurityException) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { client?.removeLocationUpdates(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Lets the background service reach the live bridge/WebView when the app is open. */
object SCBridgeHolder {
    var current: SCBridge? = null
}
