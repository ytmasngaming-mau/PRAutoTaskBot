package com.prautotaskbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.prautotaskbot.service.ScreenCaptureService

/**
 * Layar transparan untuk meminta izin "rekam layar" (hanya dipakai di Android 10 ke bawah).
 * Setelah disetujui, ScreenCaptureService dinyalakan.
 */
class ScreenCapturePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        lastAskAt = System.currentTimeMillis()
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka izin rekam layar", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Izin rekam layar ditolak. Bot tidak bisa membaca layar.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        showing = false
        lastAskAt = System.currentTimeMillis()
        super.onDestroy()
    }

    companion object {
        private const val REQ = 7701
        @Volatile var showing = false
        @Volatile var lastAskAt = 0L
    }
}
