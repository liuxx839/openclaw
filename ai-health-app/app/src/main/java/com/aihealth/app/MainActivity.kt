package com.aihealth.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val PERM_RC = 100
        private const val FILE_RC = 200
        private val PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        createChannels()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest?) { r?.let { runOnUiThread { it.grant(it.resources) } } }
                override fun onShowFileChooser(wv: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                    fileCallback?.onReceiveValue(null); fileCallback = cb
                    try { startActivityForResult(p?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, FILE_RC) }
                    catch (_: Exception) { fileCallback?.onReceiveValue(null); fileCallback = null }
                    return true
                }
                override fun onConsoleMessage(m: ConsoleMessage?) = true
            }
        }

        webView.addJavascriptInterface(Bridge(), "NativeBridge")
        setContentView(webView)
        val missing = PERMS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERM_RC) else load()
    }

    inner class Bridge {
        @JavascriptInterface
        fun scheduleReminder(title: String, body: String, timeMs: Long, id: Int) {
            val intent = Intent(this@MainActivity, MedReminderReceiver::class.java).apply {
                putExtra("title", title); putExtra("body", body); putExtra("id", id)
            }
            val pi = PendingIntent.getBroadcast(this@MainActivity, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi) }
            catch (_: Exception) { am.set(AlarmManager.RTC_WAKEUP, timeMs, pi) }
            runOnUiThread { Toast.makeText(this@MainActivity, "⏰ 提醒已设置", Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            runOnUiThread {
                val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }

        @JavascriptInterface
        fun showToast(msg: String) { runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() } }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("med_reminders", "用药提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); if (rc == PERM_RC) load() }
    private fun load() { webView.loadUrl("file:///android_asset/index.html") }
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == FILE_RC) { fileCallback?.onReceiveValue(if (res == Activity.RESULT_OK) data?.data?.let { arrayOf(it) } ?: WebChromeClient.FileChooserParams.parseResult(res, data) else null); fileCallback = null }
    }
    override fun onPause() { super.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true) }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
