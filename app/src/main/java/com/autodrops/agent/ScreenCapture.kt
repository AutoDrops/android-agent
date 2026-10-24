package com.autodrops.agent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Holds the most recent OCR text from a user-approved capture. Never populated silently. */
object ScreenText { @Volatile var last: String? = null }

/** Thin activity that asks for MediaProjection consent, then hands the token to the foreground service. */
class ScreenCaptureActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java)
                .putExtra("code", res.resultCode).putExtra("data", res.data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        }
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}

/** Foreground service (required for MediaProjection) that grabs ONE frame, OCRs it, stores text, stops. */
class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        capture(mp)
        return START_NOT_STICKY
    }

    private fun capture(mp: MediaProjection) {
        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels; val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        vd = mp.createVirtualDisplay("dron-cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
                val rowPad = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(cropped, 0))
                    .addOnSuccessListener { ScreenText.last = Ocr.cleanup(it.text) }
                    .addOnCompleteListener { cleanup(mp, vd, reader) }
            } catch (e: Exception) {
                ScreenText.last = "OCR помилка: ${e.message}"; cleanup(mp, vd, reader)
            } finally { image.close() }
        }, null)
    }

    private fun cleanup(mp: MediaProjection, vd: VirtualDisplay?, reader: ImageReader) {
        runCatching { vd?.release() }; runCatching { reader.close() }; runCatching { mp.stop() }
        stopSelf()
    }

    private fun startForegroundNotif() {
        val ch = "dron_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "Захоплення екрана", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, ch).setContentTitle("DRON.exe").setContentText("Захоплення екрана для OCR…")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(42, n)
    }
}
