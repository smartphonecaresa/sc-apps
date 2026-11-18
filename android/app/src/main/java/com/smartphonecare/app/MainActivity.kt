package com.smartphonecare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Thin native shell. ALL business logic (login, invoices, balances, the
 * admin dashboard) stays in the existing web app (track-app/index.html),
 * loaded here in a full-screen WebView exactly like the current app did.
 * The only thing this native layer adds is the "SCNative" bridge object
 * the web app already calls (see track-app finSync()): ring, lock,
 * location, photo, uninstall-block, app-block, wipe, version.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: SCBridge

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: bridge methods re-check permission state when called again */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        bridge = SCBridge(this, webView)
        setupWebView()
        requestRuntimePermissionsIfNeeded()

        val url = savedInstanceState?.getString("url") ?: BuildConfig.HUB_URL
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = s.userAgentString + " SCResellerApp/" + BuildConfig.VERSION_NAME + " (" + BuildConfig.ROLE + ")"

        webView.addJavascriptInterface(bridge, "SCNative")
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        // On a properly Device-Owner-provisioned phone these are already
        // silently granted (see SCDeviceAdminReceiver). This is only the
        // fallback for a plain sideload install.
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bridge.onDestroy()
        super.onDestroy()
    }
}
