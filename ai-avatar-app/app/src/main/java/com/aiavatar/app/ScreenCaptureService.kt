package com.aiavatar.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream

class ScreenCaptureHelper(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 1001
        private var instance: ScreenCaptureHelper? = null
        fun getInstance(activity: Activity): ScreenCaptureHelper {
            if (instance == null) instance = ScreenCaptureHelper(activity)
            return instance!!
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capturing = false
    private var onFrame: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var frameRunnable: Runnable? = null

    fun requestPermission() {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    fun onPermissionResult(resultCode: Int, data: Intent?, callback: (String) -> Unit) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        onFrame = callback
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        startCapture()
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        val width = 640
        val height = (640f * metrics.heightPixels / metrics.widthPixels).toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AIAvatarScreen", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        capturing = true
        scheduleNextFrame()
    }

    private fun scheduleNextFrame() {
        if (!capturing) return
        frameRunnable = Runnable {
            captureFrame()
            scheduleNextFrame()
        }
        handler.postDelayed(frameRunnable!!, 500) // 2fps
    }

    private fun captureFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            if (cropped !== bitmap) bitmap.recycle()

            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 50, stream)
            cropped.recycle()

            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            if (b64.length < 500 * 1024) {
                onFrame?.invoke(b64)
            }
        } finally {
            image.close()
        }
    }

    fun stop() {
        capturing = false
        frameRunnable?.let { handler.removeCallbacks(it) }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    fun isCapturing() = capturing
}
