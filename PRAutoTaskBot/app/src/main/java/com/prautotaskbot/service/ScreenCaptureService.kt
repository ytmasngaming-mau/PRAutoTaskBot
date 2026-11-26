package com.prautotaskbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager

/**
 * Potret layar untuk HP Android 10 ke bawah (takeScreenshot baru ada di Android 11).
 * Memakai izin "rekam layar" (MediaProjection). Hanya dipakai kalau Android < 11.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ready) startCapture(code, data)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Baca layar bot", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bot sedang membaca layar")
            .setContentText("Dipakai untuk potret layar di Android 10 ke bawah")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            if (p == null) {
                stopSelf()
                return
            }
            projection = p

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            val w = size.x
            val h = size.y
            val dpi = resources.displayMetrics.densityDpi

            val t = HandlerThread("PRBotCapture").also { it.start() }
            thread = t
            val handler = Handler(t.looper)

            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                }
            }, handler)

            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                try {
                    val img = rd.acquireLatestImage()
                    if (img != null) {
                        synchronized(lock) {
                            held?.close()
                            held = img
                        }
                    }
                } catch (_: Exception) {
                }
            }, handler)

            virtualDisplay = p.createVirtualDisplay(
                "PRBotCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            ready = true
        } catch (e: Exception) {
            ready = false
            stopSelf()
        }
    }

    private fun cleanup() {
        ready = false
        synchronized(lock) {
            try { held?.close() } catch (_: Exception) {}
            held = null
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_result_data"
        private const val CHANNEL_ID = "prbot_capture"
        private const val NOTIF_ID = 4711

        private val lock = Any()
        @Volatile private var held: Image? = null
        @Volatile private var ready = false

        fun isReady(): Boolean = ready

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) } catch (_: Exception) {}
        }

        /** Ambil frame layar terakhir sebagai Bitmap baru (pemanggil boleh recycle). Null kalau belum ada frame. */
        fun grab(): Bitmap? {
            synchronized(lock) {
                val img = held ?: return null
                return try {
                    val plane = img.planes[0]
                    val paddedW = plane.rowStride / plane.pixelStride
                    val bmp = Bitmap.createBitmap(paddedW, img.height, Bitmap.Config.ARGB_8888)
                    plane.buffer.rewind()
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    if (paddedW != img.width) {
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                        bmp.recycle()
                        cropped
                    } else {
                        bmp
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
