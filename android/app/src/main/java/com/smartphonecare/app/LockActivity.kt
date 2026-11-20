package com.smartphonecare.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Full-screen kiosk lock. Shown over everything when the shop turns on
 * Screen lock / Lock on overdue, or when the OfflineWatchdog auto-locks
 * after too long with no internet. On a Device-Owner phone this also
 * starts lock task mode (Home/Recents/status bar suppressed).
 *
 * Accepts either the original two-string call (SCBridge.deviceLock) or
 * the richer extras (SCBridge.deviceLockRich / OfflineWatchdog) — old
 * callers keep working unchanged.
 */
class LockActivity : Activity() {

    companion object {
        @Volatile private var current: LockActivity? = null
        fun dismiss() { current?.finishLock() }
    }

    private lateinit var statusMsg: TextView

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
        val shopName = intent.getStringExtra("shopName") ?: ""
        val custName = intent.getStringExtra("custName") ?: ""
        val dueDate = intent.getStringExtra("dueDate") ?: ""
        val shopPhone = intent.getStringExtra("shopPhone") ?: ""

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0f172a"))
        root.setPadding(60, 70, 60, 70)

        if (shopName.isNotBlank()) {
            val shop = TextView(this)
            shop.text = shopName
            shop.setTextColor(Color.parseColor("#94A3B8"))
            shop.textSize = 15f
            shop.gravity = Gravity.CENTER
            root.addView(shop)
        }

        val icon = TextView(this)
        icon.text = "🔒"
        icon.textSize = 48f
        icon.gravity = Gravity.CENTER
        icon.setPadding(0, 16, 0, 10)
        root.addView(icon)

        val header = TextView(this)
        header.text = "DEVICE LOCKED — INSTALLMENT OVERDUE"
        header.setTextColor(Color.WHITE)
        header.setTypeface(header.typeface, Typeface.BOLD)
        header.textSize = 19f
        header.gravity = Gravity.CENTER
        header.setPadding(0, 0, 0, 18)
        root.addView(header)

        if (custName.isNotBlank()) {
            val cust = TextView(this)
            cust.text = custName
            cust.setTextColor(Color.WHITE)
            cust.textSize = 16f
            cust.gravity = Gravity.CENTER
            root.addView(cust)
        }

        if (amount.isNotBlank()) {
            val amt = TextView(this)
            amt.text = "Amount due: $amount SR"
            amt.setTextColor(Color.parseColor("#F59E0B"))
            amt.setTypeface(amt.typeface, Typeface.BOLD)
            amt.textSize = 17f
            amt.gravity = Gravity.CENTER
            amt.setPadding(0, 10, 0, 0)
            root.addView(amt)
        }

        if (dueDate.isNotBlank()) {
            val due = TextView(this)
            due.text = "Due: $dueDate"
            due.setTextColor(Color.parseColor("#94A3B8"))
            due.textSize = 13f
            due.gravity = Gravity.CENTER
            root.addView(due)
        }

        val msg = TextView(this)
        msg.text = message
        msg.setTextColor(Color.parseColor("#CBD5E1"))
        msg.textSize = 14f
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, 18, 0, 18)
        root.addView(msg)

        if (shopPhone.isNotBlank()) {
            val call = Button(this)
            call.text = "Call shop"
            call.setOnClickListener {
                try {
                    val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    val i = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL)
                    i.data = Uri.parse("tel:" + Uri.encode(shopPhone))
                    startActivity(i)
                } catch (e: Exception) {}
            }
            root.addView(call)
        }

        val check = Button(this)
        check.text = "Check payment status"
        check.setPadding(0, 14, 0, 0)
        check.setOnClickListener {
            statusMsg.text = "Checking…"
            // Bring the WebView page to the front; its own visibilitychange/
            // online handlers (already wired since v2987) re-poll Firestore
            // immediately and call deviceLockClear() themselves if paid.
            try {
                val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {}
        }
        root.addView(check)

        statusMsg = TextView(this)
        statusMsg.setTextColor(Color.parseColor("#64748B"))
        statusMsg.textSize = 12f
        statusMsg.gravity = Gravity.CENTER
        statusMsg.setPadding(0, 10, 0, 0)
        root.addView(statusMsg)

        setContentView(root)

        if (SCDeviceAdminReceiver.isDeviceOwner(this)) {
            try { startLockTask() } catch (e: Exception) {}
        }
    }

    // Block the back button while locked; the shop clears this remotely,
    // or "Check payment status" clears it once paid.
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
