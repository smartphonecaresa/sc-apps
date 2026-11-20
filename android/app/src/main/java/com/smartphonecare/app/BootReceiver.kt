package com.smartphonecare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the offline watchdog (and leaves location sharing to be
 * restarted by the web app's own locStart() the next time it loads, as
 * it already does) after the phone reboots — so restarting isn't a way
 * to dodge the 72h offline failsafe.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            try { OfflineWatchdog.schedule(context) } catch (e: Exception) {}
        }
    }
}
