package com.smartphonecare.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "please pay to unlock" screen. Shown over everything when the
 * shop turns on Screen lock / Lock on overdue; closed again by
 * SCBridge.deviceLockClear(). On a Device-Owner phone this also starts lock
 * task mode so Home/Recents are suppressed; emergency calls still work
 * (Android keeps that available in lock task mode by design).
 */
class LockActivity : Activity() {

    companion object {
        @Volatile private var current: LockActivity? = null
        fun dismiss() { current?.finishLock() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val message = intent.getStringExtra("message") ?: "Please pay your installment to unlock."
        val amount = intent.getStringExtra("amount") ?: ""

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#111827"))
        root.setPadding(60, 60, 60, 60)

        val icon = TextView(this)
        icon.text = "🔒"
        icon.textSize = 54f
        icon.gravity = Gravity.CENTER
        root.addView(icon)

        val msg = TextView(this)
        msg.text = message
        msg.setTextColor(Color.WHITE)
        msg.textSize = 20f
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, 40, 0, 20)
        root.addView(msg)

        if (amount.isNotBlank()) {
            val amt = TextView(this)
            amt.text = "Amount due: $amount"
            amt.setTextColor(Color.parseColor("#F59E0B"))
            amt.textSize = 16f
            amt.gravity = Gravity.CENTER
            root.addView(amt)
        }

        setContentView(root)

        if (SCDeviceAdminReceiver.isDeviceOwner(this)) {
            try { startLockTask() } catch (e: Exception) {}
        }
    }

    // Block the back button while locked; the shop clears this remotely.
    override fun onBackPressed() { /* no-op by design */ }

    private fun finishLock() {
        try { stopLockTask() } catch (e: Exception) {}
        runOnUiThread { finish() }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
