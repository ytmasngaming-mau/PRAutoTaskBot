package com.prautotaskbot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.prautotaskbot.databinding.ActivityMainBinding
import com.prautotaskbot.service.FloatingBubbleService
import com.prautotaskbot.service.ScreenCaptureService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)

        // Cek permission overlay saat startup (untuk floating bubble)
        checkOverlayPermission()

        // HP Android 10 ke bawah: minta izin rekam layar supaya bot bisa membaca layar
        if (Build.VERSION.SDK_INT < 30 && !ScreenCaptureService.isReady()) {
            startActivity(Intent(this, ScreenCapturePermissionActivity::class.java))
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // Tidak memaksa, hanya info. User bisa aktifkan dari Settings di app.
        }
    }

    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Berikan izin 'Tampilkan di atas aplikasi lain' untuk Floating Bubble",
                Toast.LENGTH_LONG
            ).show()
        } else {
            startFloatingBubble()
        }
    }

    fun startFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingBubbleService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "Floating Bubble diaktifkan", Toast.LENGTH_SHORT).show()
    }

    fun stopFloatingBubble() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        stopService(intent)
        Toast.makeText(this, "Floating Bubble dimatikan", Toast.LENGTH_SHORT).show()
    }

    fun openPrSocial() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pr.social"))
        startActivity(intent)
    }
}
