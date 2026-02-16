package com.aiavatar.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var serviceRunning = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val FILE_CHOOSER_REQUEST_CODE = 200
        private const val REMINDER_CHANNEL_ID = "ai_avatar_reminders"
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        createReminderChannel()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.let { runOnUiThread { it.grant(it.resources) } }
                }
                override fun onShowFileChooser(wv: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = cb
                    try {
                        startActivityForResult(params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }, FILE_CHOOSER_REQUEST_CODE)
                    } catch (e: Exception) {
                        fileUploadCallback?.onReceiveValue(null); fileUploadCallback = null
                    }
                    return true
                }
                override fun onConsoleMessage(msg: ConsoleMessage?) = true
            }
        }

        webView.addJavascriptInterface(NativeBridge(), "NativeBridge")
        setContentView(webView)
        requestPermissionsIfNeeded()
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun startKeepAlive() {
            runOnUiThread { startListeningService() }
        }

        @JavascriptInterface
        fun stopKeepAlive() {
            runOnUiThread { stopListeningService() }
        }

        @JavascriptInterface
        fun openCalendarEvent(title: String, description: String, timeMillis: Long, durationMin: Int) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.Events.DESCRIPTION, description)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, timeMillis + durationMin * 60000L)
                    putExtra(CalendarContract.Events.HAS_ALARM, true)
                }
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun scheduleNotification(title: String, body: String, timeMillis: Long, id: Int) {
            val intent = Intent(this@MainActivity, ReminderReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("body", body)
                putExtra("id", id)
            }
            val pending = PendingIntent.getBroadcast(
                this@MainActivity, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.set(AlarmManager.RTC_WAKEUP, timeMillis, pending)
            } else {
                try {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pending)
                } catch (e: Exception) {
                    alarm.set(AlarmManager.RTC_WAKEUP, timeMillis, pending)
                }
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, "⏰ 提醒已设置", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun setAlarm(hour: Int, minute: Int, title: String) {
            runOnUiThread {
                val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, title)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法设置闹钟: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun startScreenCapture() {
            runOnUiThread { ScreenCaptureHelper.getInstance(this@MainActivity).requestPermission() }
        }

        @JavascriptInterface
        fun stopScreenCapture() {
            runOnUiThread { ScreenCaptureHelper.getInstance(this@MainActivity).stop() }
        }

        @JavascriptInterface
        fun isScreenCapturing(): Boolean {
            return ScreenCaptureHelper.getInstance(this@MainActivity).isCapturing()
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            runOnUiThread {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(android.os.VibratorManager::class.java)
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms)
                }
            }
        }

        @JavascriptInterface
        fun shareImage(base64Data: String, title: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val file = File(cacheDir, "share_card_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "分享卡片"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveImageToGallery(base64Data: String, fileName: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIAvatar")
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) } }
                    Toast.makeText(this@MainActivity, "✅ 已保存到相册", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(REMINDER_CHANNEL_ID, "AI 提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI Avatar 设置的提醒"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = REQUIRED_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        else loadApp()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); if (rc == PERMISSION_REQUEST_CODE) loadApp()
    }

    private fun loadApp() { webView.loadUrl("file:///android_asset/index.html") }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == FILE_CHOOSER_REQUEST_CODE) {
            fileUploadCallback?.onReceiveValue(if (res == Activity.RESULT_OK) data?.data?.let { arrayOf(it) } ?: WebChromeClient.FileChooserParams.parseResult(res, data) else null)
            fileUploadCallback = null
        }
        if (rc == ScreenCaptureHelper.REQUEST_CODE) {
            ScreenCaptureHelper.getInstance(this).onPermissionResult(res, data) { base64Frame ->
                runOnUiThread {
                    webView.evaluateJavascript("if(typeof onScreenFrame==='function')onScreenFrame('$base64Frame')", null)
                }
            }
        }
    }

    override fun onPause() { super.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true) }
    override fun onDestroy() { stopListeningService(); webView.destroy(); super.onDestroy() }

    private fun startListeningService() {
        if (serviceRunning) return
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        serviceRunning = true
    }

    private fun stopListeningService() {
        if (!serviceRunning) return
        stopService(Intent(this, KeepAliveService::class.java)); serviceRunning = false
    }
}
