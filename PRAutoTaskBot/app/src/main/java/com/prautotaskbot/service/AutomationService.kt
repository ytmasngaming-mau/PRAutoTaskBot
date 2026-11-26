package com.prautotaskbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bot otomatis PR.SOCIAL — Instagram + TikTok Follow.
 *
 * Prioritas:
 * 1. Selesaikan dulu tugas yang "Sedang dikerjakan"
 * 2. TikTok (prioritas 2)
 * 3. Instagram
 *
 * Instagram khusus:
 * - Setiap 10 misi selesai → jeda 3-5 menit
 * - Limit 50/jam DIHAPUS
 * - Deteksi banner cooldown website → setelah hilang jeda ekstra 5-10 menit
 *
 * TikTok mengikuti alur yang sama seperti di video tutorial.
 */
class AutomationService : AccessibilityService() {

    private enum class Phase {
        LIST,
        IN_INSTAGRAM, IG_CLICKED,
        IN_TIKTOK, TT_CLICKED,
        RETURNING, CHECKING
    }

    private enum class Platform { INSTAGRAM, TIKTOK, UNKNOWN }

    private class OcrWord(val raw: String, val rect: Rect) {
        val norm: String = raw.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private class OcrLine(val text: String, val rect: Rect, val words: List<OcrWord>) {
        val norm: String = text.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var loopJob: Job? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var toggleView: TextView? = null
    private var statusView: TextView? = null

    private var enabled = false
    private var phase = Phase.LIST
    private var phaseSince = 0L
    private var currentPkg: String? = null
    private var browserPkg: String? = null
    private var checkAttempts = 0
    private var platformRetries = 0
    private var platformAttempts = 0
    private var targetUser: String? = null
    private var tapY = -1
    private var verifiedFollow = false
    private var cancelAfterReturn = false
    private var idleTicks = 0
    private var lastRefreshAt = 0L
    private var nextAllowedAt = 0L
    private var doneCount = 0
    private var lastShotError = 0
    private val doneTimes = ArrayList<Long>()
    private var statusText = "Siap. Tekan tombol untuk mulai."
    private var currentPlatform = Platform.UNKNOWN

    // Cooldown website (khusus Instagram)
    private var wasInWebsiteCooldown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        startLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            p != packageName && p != "com.android.systemui"
        ) {
            currentPkg = p
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        loopJob?.cancel()
        job.cancel()
        overlay?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    // ---------------------------------------------------------------- UI

    private fun showOverlay() {
        val toggle = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            setOnClickListener { toggleEnabled() }
        }
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 8, 16, 8)
            maxWidth = 520
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(status)
        }
        toggleView = toggle
        statusView = status
        overlay = box

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }
        try {
            windowManager?.addView(box, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal tampilkan tombol: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshUi()
    }

    private fun toggleEnabled() {
        enabled = !enabled
        if (enabled) {
            resetTask()
            nextAllowedAt = 0L
            wasInWebsiteCooldown = false
            setStatus("Bot jalan. Buka halaman Tugas.")
        } else {
            setStatus("Bot dimatikan.")
        }
    }

    private fun refreshUi() {
        toggleView?.apply {
            text = if (enabled) "🤖 AUTO: ON" else "🤖 AUTO: OFF"
            setBackgroundColor(if (enabled) 0xEE2E7D32.toInt() else 0xEEC62828.toInt())
        }
        statusView?.text = "$statusText\n✔ $doneCount selesai | IG setiap 10 → jeda 3-5 mnt"
    }

    private fun setStatus(s: String) {
        statusText = s
        refreshUi()
    }

    // ---------------------------------------------------------------- Loop

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                delay(Random.nextLong(700, 1100))
                if (!enabled) continue
                try {
                    tick()
                } catch (e: Exception) {
                    setStatus("Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val pkg = rootInActiveWindow?.packageName?.toString() ?: currentPkg
        if (pkg == packageName) return

        val bmp = captureBitmap()
        if (bmp == null) {
            setStatus("Gagal potret layar (kode $lastShotError)")
            return
        }
        val all = ocr(bmp)
        bmp.recycle()
        val ov = overlayRect()
        val lines = all.filter { !ov.contains(it.rect.centerX(), it.rect.centerY()) }

        // === Di dalam aplikasi Instagram ===
        if (pkg == IG_PACKAGE) {
            handleInstagram(lines, now)
            return
        }

        // === Di dalam aplikasi TikTok ===
        if (pkg in TT_PACKAGES) {
            handleTikTok(lines, now)
            return
        }

        // === Di halaman PR.SOCIAL ===
        val looksPr = lines.any {
            it.norm.contains("ikuti di") || it.norm.contains("periksa follow saya") ||
                it.norm.contains("buka profil dan ikuti") || it.norm.contains("tugas saya") ||
                it.norm.contains("batas per jam") || it.norm.contains("jeda untuk menjaga") ||
                it.norm.contains("sedang dikerjakan") || it.norm.contains("tugas berikutnya")
        }
        if (looksPr) {
            if (pkg != null) browserPkg = pkg
            handlePr(lines, now)
        } else {
            setStatus("Halaman Tugas tidak terlihat (OCR ${lines.size} baris)")
        }
    }

    // ---------------------------------------------------------------- Screenshot + OCR

    private suspend fun captureBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < 30) {
            lastShotError = -1
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            cont.resume(soft)
                        } catch (e: Exception) {
                            lastShotError = -2
                            cont.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastShotError = errorCode
                        cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            lastShotError = -3
            cont.resume(null)
        }
    }

    private suspend fun ocr(bmp: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val out = ArrayList<OcrLine>()
                for (b in t.textBlocks) {
                    for (l in b.lines) {
                        val r = l.boundingBox ?: continue
                        val ws = ArrayList<OcrWord>()
                        for (e in l.elements) {
                            val er = e.boundingBox ?: continue
                            ws.add(OcrWord(e.text, er))
                        }
                        out.add(OcrLine(l.text, r, ws))
                    }
                }
                cont.resume(out)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    private fun overlayRect(): Rect {
        val o = overlay ?: return Rect()
        val loc = IntArray(2)
        o.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + o.width, loc[1] + o.height)
    }

    // ---------------------------------------------------------------- Instagram (app)

    private fun handleInstagram(lines: List<OcrLine>, now: Long) {
        if (lines.any {
                it.norm.contains("coba lagi nanti") || it.norm.contains("try again later") ||
                    it.norm.contains("tindakan diblokir") || it.norm.contains("action blocked")
            }
        ) {
            enabled = false
            setStatus("⛔ Instagram membatasi akun. Bot dimatikan.")
            Toast.makeText(this, "Instagram membatasi aksi. Bot dimatikan.", Toast.LENGTH_LONG).show()
            return
        }

        val h = resources.displayMetrics.heightPixels
        val allWords = lines.flatMap { it.words }

        val statY = allWords
            .filter { it.norm == "postingan" || it.norm == "posts" || it.norm == "pengikut" || it.norm == "followers" }
            .filter { it.rect.centerY() < h * 0.30 }
            .maxOfOrNull { it.rect.centerY() } ?: (h * 0.15).toInt()
        val topLimit = statY + 40

        val headingY = lines
            .filter { it.norm.contains("disarankan untuk") || it.norm.contains("suggested for") }
            .minOfOrNull { it.rect.top }
        val bottomLimit = headingY?.minus(10) ?: (h * 0.88).toInt()

        val zoneLines = lines.filter { l ->
            l.words.size <= 6 && l.rect.centerY() in topLimit until bottomLimit
        }

        val strictLines = zoneLines.filter { l ->
            l.words.all { it.norm in BUTTON_WORDS || it.norm.length <= 2 || it.norm.startsWith("mengik") }
        }

        val anchorYs = zoneLines.flatMap { it.words }
            .filter { it.norm in ANCHOR_WORDS }
            .map { it.rect.centerY() }

        fun pick(pred: (OcrWord) -> Boolean): OcrWord? {
            if (anchorYs.isNotEmpty()) {
                zoneLines.flatMap { it.words }
                    .filter { pred(it) && anchorYs.any { y -> abs(y - it.rect.centerY()) < 70 } }
                    .minByOrNull { it.rect.centerY() }
                    ?.let { return it }
            }
            strictLines.flatMap { it.words }
                .filter { pred(it) }
                .minByOrNull { it.rect.centerY() }
                ?.let { return it }
            return zoneLines.filter { it.words.size <= 3 }
                .flatMap { it.words }
                .filter { pred(it) }
                .minByOrNull { it.rect.centerY() }
        }

        val following = pick { it.norm.startsWith("mengik") || it.norm in FOLLOWING_WORDS }
        val followBtn = pick { it.norm in FOLLOW_WORDS }
        val followingOk = following != null && (tapY < 0 || abs(following.rect.centerY() - tapY) < 150)
        val onTarget = headerMatchesTarget(lines, h)

        when (phase) {
            Phase.IN_INSTAGRAM -> {
                if (!onTarget) {
                    if (now - phaseSince > PROFILE_TIMEOUT) {
                        retryProfile(now, "Profil bukan target")
                    } else {
                        setStatus("Menunggu profil IG @${targetUser ?: "?"}…")
                    }
                } else if (following != null) {
                    verifiedFollow = true
                    returnToBrowser(now, "Sudah mengikuti (IG)")
                } else if (followBtn != null) {
                    tap(followBtn.rect)
                    tapY = followBtn.rect.centerY()
                    phase = Phase.IG_CLICKED
                    phaseSince = now
                    platformRetries = 0
                    setStatus("Menekan tombol Ikuti (IG)…")
                } else if (now - phaseSince > PROFILE_TIMEOUT) {
                    retryProfile(now, "Tombol Ikuti IG tidak ketemu")
                } else {
                    setStatus("Mencari tombol Ikuti IG… (${platformAttempts + 1}/$MAX_PROFILE_TRIES)")
                }
            }
            Phase.IG_CLICKED -> {
                if (onTarget && followingOk) {
                    verifiedFollow = true
                    returnToBrowser(now, "Berhasil mengikuti (IG)")
                } else if (now - phaseSince > 3500) {
                    if (onTarget && followBtn != null && platformRetries < 2) {
                        platformRetries++
                        tap(followBtn.rect)
                        phaseSince = now
                    } else {
                        retryProfile(now, "Ikuti IG belum berhasil")
                    }
                }
            }
            Phase.RETURNING -> {
                if (now - phaseSince > 3000) {
                    launchBrowser()
                    phaseSince = now
                }
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- TikTok (app) — mengikuti alur video

    private fun handleTikTok(lines: List<OcrLine>, now: Long) {
        // Deteksi limit TikTok
        if (lines.any {
                it.norm.contains("coba lagi nanti") || it.norm.contains("try again later") ||
                    it.norm.contains("tindakan diblokir") || it.norm.contains("action blocked") ||
                    it.norm.contains("terlalu banyak") || it.norm.contains("too many")
            }
        ) {
            enabled = false
            setStatus("⛔ TikTok membatasi akun. Bot dimatikan.")
            Toast.makeText(this, "TikTok membatasi aksi. Bot dimatikan.", Toast.LENGTH_LONG).show()
            return
        }

        val h = resources.displayMetrics.heightPixels
        val allWords = lines.flatMap { it.words }

        // Area tombol follow biasanya di bagian atas profil (di bawah username & statistik)
        val topLimit = (h * 0.12).toInt()
        val bottomLimit = (h * 0.45).toInt()

        val zoneWords = allWords.filter { it.rect.centerY() in topLimit until bottomLimit }

        // Cari tombol "Ikuti" / "Follow"
        val followBtn = zoneWords
            .filter { it.norm in FOLLOW_WORDS }
            .minByOrNull { it.rect.centerY() }

        // Sudah mengikuti? "Mengikuti", "Following", "Pesan", "Message"
        val following = zoneWords
            .filter {
                it.norm.startsWith("mengik") || it.norm in FOLLOWING_WORDS ||
                    it.norm == "pesan" || it.norm == "message"
            }
            .minByOrNull { it.rect.centerY() }

        val followingOk = following != null && (tapY < 0 || abs(following.rect.centerY() - tapY) < 180)
        val onTarget = headerMatchesTarget(lines, h)

        when (phase) {
            Phase.IN_TIKTOK -> {
                if (!onTarget) {
                    if (now - phaseSince > PROFILE_TIMEOUT) {
                        retryProfile(now, "Profil TikTok bukan target")
                    } else {
                        setStatus("Menunggu profil TT @${targetUser ?: "?"}…")
                    }
                } else if (following != null) {
                    verifiedFollow = true
                    returnToBrowser(now, "Sudah mengikuti (TT)")
                } else if (followBtn != null) {
                    tap(followBtn.rect)
                    tapY = followBtn.rect.centerY()
                    phase = Phase.TT_CLICKED
                    phaseSince = now
                    platformRetries = 0
                    setStatus("Menekan tombol Ikuti (TikTok)…")
                } else if (now - phaseSince > PROFILE_TIMEOUT) {
                    retryProfile(now, "Tombol Ikuti TikTok tidak ketemu")
                } else {
                    setStatus("Mencari tombol Ikuti TT… (${platformAttempts + 1}/$MAX_PROFILE_TRIES)")
                }
            }
            Phase.TT_CLICKED -> {
                if (onTarget && followingOk) {
                    verifiedFollow = true
                    returnToBrowser(now, "Berhasil mengikuti (TT)")
                } else if (now - phaseSince > 4000) {
                    if (onTarget && followBtn != null && platformRetries < 2) {
                        platformRetries++
                        tap(followBtn.rect)
                        phaseSince = now
                    } else {
                        retryProfile(now, "Ikuti TikTok belum berhasil")
                    }
                }
            }
            Phase.RETURNING -> {
                if (now - phaseSince > 3000) {
                    launchBrowser()
                    phaseSince = now
                }
            }
            else -> {}
        }
    }

    private fun headerMatchesTarget(lines: List<OcrLine>, h: Int): Boolean {
        val t = targetUser?.filter { it.isLetterOrDigit() } ?: return true
        if (t.length < 3) return true
        val hdr = lines
            .filter { it.rect.centerY() in (h * 0.03).toInt()..(h * 0.18).toInt() }
            .joinToString("") { it.norm }
            .filter { it.isLetterOrDigit() }
        if (hdr.isEmpty()) return true
        if (hdr.contains(t)) return true
        if (hdr.length >= 5 && t.contains(hdr)) return true
        var n = 0
        while (n < hdr.length && n < t.length && hdr[n] == t[n]) n++
        return n >= minOf(6, t.length)
    }

    private fun parseTarget(lines: List<OcrLine>): String? {
        val badge = lines.firstOrNull { it.norm.contains("sedang dikerjakan") } ?: return null
        val w = lines.flatMap { it.words }.firstOrNull {
            it.raw.startsWith("@") && abs(it.rect.centerY() - badge.rect.centerY()) < 80
        } ?: return null
        val name = w.raw.removePrefix("@").lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        return name.ifEmpty { null }
    }

    private fun returnToBrowser(now: Long, msg: String) {
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg, kembali ke halaman tugas")
    }

    private fun retryProfile(now: Long, msg: String) {
        platformAttempts++
        verifiedFollow = false
        tapY = -1
        cancelAfterReturn = platformAttempts >= MAX_PROFILE_TRIES
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg. Ulangi ($platformAttempts/$MAX_PROFILE_TRIES)")
    }

    // ---------------------------------------------------------------- Halaman tugas PR.SOCIAL

    private fun handlePr(lines: List<OcrLine>, now: Long) {
        // === DETEKSI BANNER COOLDOWN WEBSITE (Instagram) ===
        val hasCooldownBanner = lines.any {
            it.norm.contains("batas per jam tercapai") ||
            it.norm.contains("jeda untuk menjaga") ||
            (it.norm.contains("batas per jam") && it.norm.contains("50"))
        }

        if (hasCooldownBanner) {
            wasInWebsiteCooldown = true
            setStatus("⏳ Website cooldown (batas 50). Menunggu banner hilang…")
            return
        }

        if (wasInWebsiteCooldown) {
            wasInWebsiteCooldown = false
            val extraPause = Random.nextLong(5 * 60_000L, 10 * 60_000L)
            nextAllowedAt = now + extraPause
            setStatus("✅ Cooldown website selesai. Jeda ekstra ${extraPause / 60000} menit…")
            return
        }

        val check = lines.firstOrNull { it.norm.contains("periksa follow saya") }
        val reviewed = lines.any { it.norm.contains("ditinjau") || it.norm.contains("dikirim untuk ditinjau") }
        val startBtn = lines.firstOrNull {
            it.norm.startsWith("mulai") && !it.norm.contains("dalam") && it.norm.any { c -> c.isDigit() }
        }
        val confirmMark = lines.any { it.norm.contains("konfirmasi follow anda") }
        val modalOpen = check != null || startBtn != null || confirmMark || reviewed

        // Selesai dicek
        if (phase == Phase.CHECKING && (check == null || reviewed)) {
            onTaskDone(now)
            return
        }

        // Tombol Mulai
        if (check == null && startBtn != null) {
            if (!canStartNew(now)) return
            tap(startBtn.rect)
            phase = Phase.LIST
            phaseSince = now
            setStatus("Menekan Mulai…")
            return
        }

        // Tombol Tugas berikutnya
        val nextBtn = lines.firstOrNull { it.norm.contains("tugas berikutnya") }
        if (check == null && nextBtn != null) {
            if (!canStartNew(now)) return
            tap(nextBtn.rect)
            phase = Phase.LIST
            phaseSince = now
            setStatus("Lanjut ke tugas berikutnya…")
            return
        }

        if (!modalOpen) {
            if (phase == Phase.LIST) {
                listStep(lines, now)
            } else if (now - phaseSince > 15000) {
                resetTask()
            } else {
                setStatus("Menunggu halaman tugas…")
            }
            return
        }

        if (check == null) {
            setStatus("Menunggu jendela tugas…")
            return
        }

        // Modal terbuka + ada "Periksa follow saya"
        when (phase) {
            Phase.LIST -> {
                if (!canStartNew(now)) return
                targetUser = parseTarget(lines)
                currentPlatform = detectPlatformFromModal(lines)
                tapLink(lines)
                phase = if (currentPlatform == Platform.TIKTOK) Phase.IN_TIKTOK else Phase.IN_INSTAGRAM
                phaseSince = now
                checkAttempts = 0
                platformRetries = 0
                platformAttempts = 0
                verifiedFollow = false
                cancelAfterReturn = false
                tapY = -1
                setStatus("Membuka profil ${if (currentPlatform == Platform.TIKTOK) "TikTok" else "Instagram"}…")
            }
            Phase.IN_INSTAGRAM, Phase.IN_TIKTOK -> {
                // Masih di halaman tugas: link belum membuka app
                if (now - phaseSince > 8000) {
                    platformAttempts++
                    if (platformAttempts >= MAX_PROFILE_TRIES) {
                        cancelTask(lines, now)
                    } else {
                        tapLink(lines)
                        phaseSince = now
                        setStatus("Link belum terbuka, coba lagi ($platformAttempts/$MAX_PROFILE_TRIES)")
                    }
                }
            }
            Phase.IG_CLICKED, Phase.TT_CLICKED, Phase.RETURNING -> {
                if (cancelAfterReturn) {
                    cancelTask(lines, now)
                } else if (!verifiedFollow) {
                    tapLink(lines)
                    phase = if (currentPlatform == Platform.TIKTOK) Phase.IN_TIKTOK else Phase.IN_INSTAGRAM
                    phaseSince = now
                    setStatus("Membuka ulang profil…")
                } else {
                    tap(check.rect)
                    phase = Phase.CHECKING
                    phaseSince = now
                    checkAttempts = 1
                    setStatus("Follow terverifikasi, menekan Periksa…")
                }
            }
            Phase.CHECKING -> {
                if (now - phaseSince > 7000) {
                    if (checkAttempts >= 3) {
                        cancelTask(lines, now)
                    } else {
                        tap(check.rect)
                        checkAttempts++
                        phaseSince = now
                        setStatus("Coba periksa lagi ($checkAttempts/3)")
                    }
                }
            }
        }
    }

    private fun detectPlatformFromModal(lines: List<OcrLine>): Platform {
        val text = lines.joinToString(" ") { it.norm }
        return when {
            text.contains("tiktok") || text.contains("ikuti di tiktok") -> Platform.TIKTOK
            text.contains("instagram") || text.contains("ikuti di instagram") -> Platform.INSTAGRAM
            else -> currentPlatform
        }
    }

    private fun listStep(lines: List<OcrLine>, now: Long) {
        if (!canStartNew(now)) return

        val h = resources.displayMetrics.heightPixels

        // 1. Prioritas: apakah sudah ada modal "Sedang dikerjakan"? (sudah dihandle di handlePr)
        // 2. Cari tugas TikTok dulu (prioritas 2)
        val ttRow = lines
            .filter {
                it.norm.contains("ikuti di tiktok") &&
                    it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.85
            }
            .minByOrNull { it.rect.top }

        // 3. Cari tugas Instagram
        val igRow = lines
            .filter {
                it.norm.contains("ikuti di instagram") &&
                    it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.85
            }
            .minByOrNull { it.rect.top }

        // Coba klik tab TikTok kalau sedang di Instagram dan ada indikasi tugas TikTok
        val onTikTokTab = lines.any {
            (it.norm.contains("tiktok") && it.norm.contains("tugas")) ||
            (it.norm.contains("@") && lines.any { l -> l.norm.contains("ikuti di tiktok") })
        }
        val onIgTab = lines.any {
            (it.norm.contains("instagram") && it.norm.contains("tugas"))
        }

        // Kalau lagi di tab Instagram dan tidak ada tugas IG, coba pindah ke TikTok
        if (igRow == null && ttRow == null) {
            // Tidak ada tugas visible → coba switch tab atau refresh
            val tiktokTab = lines.firstOrNull {
                it.norm == "tiktok" || (it.norm.contains("tiktok") && it.words.size <= 2)
            }
            if (tiktokTab != null && !onTikTokTab) {
                tap(tiktokTab.rect)
                setStatus("Pindah ke tab TikTok…")
                phaseSince = now
                return
            }

            idleTicks++
            setStatus("Tidak ada tugas Follow terlihat")
            if (idleTicks >= 4 && now - lastRefreshAt > 15000) {
                idleTicks = 0
                lastRefreshAt = now
                setStatus("Tugas habis, memuat ulang halaman…")
                // Prefer refresh ke Instagram dulu sesuai request
                refreshPage(preferInstagram = true)
            }
            return
        }

        idleTicks = 0

        // Prioritas TikTok dulu
        if (ttRow != null) {
            currentPlatform = Platform.TIKTOK
            tap(ttRow.rect)
            phaseSince = now
            setStatus("Membuka tugas TikTok…")
            return
        }

        // Lalu Instagram
        if (igRow != null) {
            currentPlatform = Platform.INSTAGRAM
            tap(igRow.rect)
            phaseSince = now
            setStatus("Membuka tugas Instagram…")
        }
    }

    private fun tapLink(lines: List<OcrLine>) {
        val link = when (currentPlatform) {
            Platform.TIKTOK -> {
                lines.firstOrNull { it.norm.contains("tiktok.com") }
                    ?: lines.firstOrNull { it.norm.contains("buka profil dan ikuti") }
            }
            else -> {
                lines.firstOrNull { it.norm.contains("instagram.com") }
                    ?: lines.firstOrNull { it.norm.contains("buka profil dan ikuti") }
            }
        }
        if (link != null) tap(link.rect)
    }

    private fun canStartNew(now: Long): Boolean {
        if (now < nextAllowedAt) {
            val sisa = (nextAllowedAt - now) / 1000
            val menit = sisa / 60
            val detik = sisa % 60
            setStatus("Jeda ${if (menit > 0) "$menit mnt " else ""}$detik dtk…")
            return false
        }
        return true
    }

    private fun onTaskDone(now: Long) {
        doneCount++
        doneTimes.add(now)
        resetTask()

        // Setiap 10 misi (total) → jeda 3-5 menit (request khusus Instagram, tapi diterapkan global biar aman)
        if (doneCount % 10 == 0) {
            val longPause = Random.nextLong(3 * 60_000L, 5 * 60_000L)
            nextAllowedAt = now + longPause
            setStatus("✅ $doneCount tugas selesai. Jeda 3-5 menit (setiap 10 misi)…")
        } else {
            nextAllowedAt = now + Random.nextLong(2000, 4500)
            setStatus("✅ Tugas selesai. Jeda sebelum tugas berikutnya.")
        }
    }

    private fun cancelTask(lines: List<OcrLine>, now: Long) {
        lines.firstOrNull { it.norm.contains("batalkan tugas") }?.let { tap(it.rect) }
        resetTask()
        nextAllowedAt = now + 4000
        setStatus("Tugas dibatalkan (gagal). Lanjut berikutnya.")
    }

    private fun resetTask() {
        phase = Phase.LIST
        phaseSince = System.currentTimeMillis()
        checkAttempts = 0
        platformRetries = 0
        platformAttempts = 0
        verifiedFollow = false
        cancelAfterReturn = false
        tapY = -1
        targetUser = null
    }

    // ---------------------------------------------------------------- Aksi

    private fun launchBrowser() {
        val pkg = browserPkg ?: return
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (_: Exception) {}
    }

    private fun refreshPage(preferInstagram: Boolean = false) {
        val pkg = browserPkg ?: return
        try {
            val url = if (preferInstagram) {
                "https://pr.social/tasks?net=instagram"
            } else {
                "https://pr.social/tasks"
            }
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {}
    }

    private fun tap(r: Rect): Boolean {
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val IG_PACKAGE = "com.instagram.android"
        private val TT_PACKAGES = setOf(
            "com.zhiliaoapp.musically",   // TikTok global
            "com.ss.android.ugc.trill"    // TikTok beberapa region
        )
        private const val MAX_PROFILE_TRIES = 5
        private const val PROFILE_TIMEOUT = 25_000L

        private val FOLLOW_WORDS = setOf("ikuti", "follow")
        private val FOLLOWING_WORDS = setOf("mengikuti", "following", "diminta", "requested")
        private val ANCHOR_WORDS = setOf(
            "kirim", "pesan", "message", "berlangganan", "subscribe", "kontak", "hubungi",
            "contact", "email", "telepon", "call", "petunjuk", "directions"
        )
        private val BUTTON_WORDS = setOf(
            "ikuti", "follow", "balik", "back", "kirim", "pesan", "message",
            "berlangganan", "subscribe", "mengikuti", "following", "diminta", "requested",
            "kontak", "hubungi", "contact", "email", "telepon", "call", "petunjuk", "directions"
        )
    }
}
