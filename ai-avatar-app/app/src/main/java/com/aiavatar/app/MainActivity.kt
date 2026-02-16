package com.aiavatar.app

import android.Manifest
import android.app.Activity
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
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var serviceRunning = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val FILE_CHOOSER_REQUEST_CODE = 200
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is in foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.let {
                        runOnUiThread { it.grant(it.resources) }
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }

                    try {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    } catch (e: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true
                }
            }
        }

        // JS bridge: let web page control the foreground service
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun startKeepAlive() {
                runOnUiThread { startListeningService() }
            }
            @JavascriptInterface
            fun stopKeepAlive() {
                runOnUiThread { stopListeningService() }
            }
        }, "NativeBridge")

        setContentView(webView)
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            loadApp()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            loadApp()
        }
    }

    private fun loadApp() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val result = if (resultCode == Activity.RESULT_OK) {
                data?.data?.let { arrayOf(it) } ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(result)
            fileUploadCallback = null
        }
    }

    // Keep WebView running when app goes to background
    override fun onPause() {
        super.onPause()
        // Do NOT call webView.onPause() — we want JS to keep running
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Move to background instead of destroying
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        stopListeningService()
        webView.destroy()
        super.onDestroy()
    }

    private fun startListeningService() {
        if (serviceRunning) return
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceRunning = true
    }

    private fun stopListeningService() {
        if (!serviceRunning) return
        stopService(Intent(this, KeepAliveService::class.java))
        serviceRunning = false
    }
}
