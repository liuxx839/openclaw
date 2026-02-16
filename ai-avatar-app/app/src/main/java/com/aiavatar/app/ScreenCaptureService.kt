package com.aiavatar.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val REQUEST_CODE = 1001
        const val CHANNEL_ID = "screen_capture"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val TAG = "ScreenCapture"

        var isCapturing = false
            private set

        // Frame buffer for JS to pull from
        @Volatile var latestFrame: String? = null
        @Volatile var frameCount: Long = 0
        @Volatile var lastError: String = ""
        @Volatile var debugInfo: String = ""

        fun stop(context: Context) {
            latestFrame = null
            frameCount = 0
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Dedicated background thread for image processing
        handlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid result: code=$resultCode data=$data")
            stopSelf()
            return START_NOT_STICKY
        }

        debugInfo = "step1:startForeground API=${Build.VERSION.SDK_INT}"
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            debugInfo += " → OK"
            Log.d(TAG, "Foreground started successfully")
        } catch (e: Exception) {
            lastError = "startForeground失败: ${e.message}"
            debugInfo += " → FAIL: ${e.message}"
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        debugInfo += " | step2:startCapture"
        try {
            startCapture(resultCode, data)
            debugInfo += " → OK"
        } catch (e: Exception) {
            lastError = "startCapture失败: ${e.message}"
            debugInfo += " → FAIL: ${e.message}"
            Log.e(TAG, "startCapture failed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        debugInfo += "|proj"
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            lastError = "getMediaProjection=null"; debugInfo += "=NULL"; stopSelf(); return
        }
        debugInfo += "=OK"

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getMetrics(metrics)
        val cw = 480; val ch = (cw.toFloat() * metrics.heightPixels / metrics.widthPixels).toInt()
        debugInfo += "|${cw}x${ch}d${metrics.densityDpi}"

        debugInfo += "|IR"
        imageReader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 3)
        debugInfo += "=OK"

        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 450) {
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            lastFrameTime = now
            processFrame(reader)
        }, handler)
        debugInfo += "|listener=OK"

        debugInfo += "|VD"
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "AIAvatarScreen", cw, ch, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        if (virtualDisplay == null) {
            lastError = "VirtualDisplay=null"; debugInfo += "=NULL"; stopSelf(); return
        }
        debugInfo += "=OK"

        isCapturing = true
        debugInfo += "|✅CAPTURING"
        Log.d(TAG, debugInfo)
    }

    private fun processFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmpWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual size (remove padding)
            val cropped = if (bmpWidth != image.width) {
                val c = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                c
            } else bitmap

            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 40, stream)
            cropped.recycle()

            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            if (b64.length < 450 * 1024) {
                latestFrame = b64
                frameCount++
                if (frameCount % 20 == 1L) {
                    Log.d(TAG, "Frame #$frameCount stored: ${b64.length / 1024}KB")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        isCapturing = false
        latestFrame = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        Log.d(TAG, "Capture stopped")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "屏幕共享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI Avatar 正在共享屏幕"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent.apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Avatar")
            .setContentText("🖥️ 正在共享屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
