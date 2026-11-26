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
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 * Bot otomatis tugas "Ikuti di TikTok" dan "Ikuti di Instagram" di PR.SOCIAL.
 * Urutan prioritas: (1) "Tugas sedang dikerjakan", (2) TikTok, (3) Instagram.
 * Jeda 10 misi dan jeda tambahan setelah batas situs berlaku khusus Instagram.
 * Cara kerja: potret layar -> baca tulisan (OCR, di HP, tanpa internet) -> ketuk posisi tulisan.
 * Tidak bergantung pada browser mana pun.
 */
class AutomationService : AccessibilityService() {

    private enum class Phase { LIST, IN_INSTAGRAM, IG_CLICKED, RETURNING, CHECKING }

    private enum class Platform(val label: String, val rowKey: String, val domain: String) {
        TIKTOK("TikTok", "ikuti di tiktok", "tiktok.com"),
        INSTAGRAM("Instagram", "ikuti di instagram", "instagram.com")
    }

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
    private var overlayParams: WindowManager.LayoutParams? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var toggleView: TextView? = null
    private var statusView: TextView? = null

    private var enabled = false
    private var phase = Phase.LIST
    private var phaseSince = 0L
    private var currentPkg: String? = null
    private var browserPkg: String? = null
    private var checkAttempts = 0
    private var igRetries = 0
    private var igAttempts = 0
    private var targetUser: String? = null
    private var tapY = -1
    private var verifiedFollow = false
    private var cancelAfterReturn = false
    private var idleTicks = 0
    private var lastRefreshAt = 0L
    private var nextAllowedAt = 0L
    private var doneCount = 0
    private var lastShotError = 0
    private var taskPlatform = Platform.INSTAGRAM
    private var tabTaps = 0
    private var tabTapsFor: Platform? = null
    private val blockedUntil = mutableMapOf<Platform, Long>()   // jeda dari situs / pembatasan app
    private val emptyUntil = mutableMapOf<Platform, Long>()     // misi follow platform ini habis
    private var waitingAll = false
    private val zeroTicks = mutableMapOf<Platform, Int>()   // berapa tick berturut-turut angka tab = 0
    private var loadingTicks = 0
    private var lastProfileHdr = ""            // judul profil terakhir yang sudah diproses
    private var acceptedProfileHdr: String? = null // profil beda nama dari situs yang diterima
    private var mismatchHdr = ""
    private var mismatchSince = 0L
    private var refreshAfterDone = false   // refresh web setiap satu misi selesai
    private val lastCount = mutableMapOf<Platform, Int>()      // angka di tab platform (jumlah misi)
    private val emptyAtCount = mutableMapOf<Platform, Int>()   // jumlah misi saat platform ditandai habis
    private var sheetTries = 0
    private val staleList = mutableSetOf<Platform>()   // platform yang daftarnya sudah basi karena jeda
    private var lastLikeCancelAt = 0L
    private var likeCancelTries = 0
    private var igDone = 0   // misi Instagram selesai (untuk jeda tiap 10 misi)
    private var shotSeenTicks = 0
    private var lastTabTapAt = 0L
    private var resumeTaps = 0
    private var lastResumeAt = 0L
    private var linkWaitSince = 0L
    private var taskRefreshes = 0
    private var pageLoadUntil = 0L
    private var stallSince = 0L
    private var stallMarked = false
    private var igPreTapAt = 0L         // waktu tombol Ikuti Instagram pertama terlihat (untuk jeda sebelum menekan)
    private var igPreTapDelay = 0L
    private var statusText = "Siap. Tekan tombol untuk mulai."

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
        overlayParams = params
        attachDrag(toggle, params) { toggleEnabled() }   // ketuk = ON/OFF, geser = pindahkan bubble
        attachDrag(status, params, null)                 // teks status juga bisa dipakai menggeser
        try {
            windowManager?.addView(box, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal tampilkan tombol: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshUi()
    }

    /** Bubble bisa digeser: tahan lalu seret. Ketukan singkat (tanpa geser) tetap ON/OFF. */
    private fun attachDrag(v: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val dm = resources.displayMetrics
                        val maxX = (dm.widthPixels - (overlay?.width ?: 300)).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - (overlay?.height ?: 200)).coerceAtLeast(0)
                        // gravity END: x = jarak dari tepi kanan, jadi geser ke kanan = x mengecil
                        params.x = (startX - dx).toInt().coerceIn(0, maxX)
                        params.y = (startY + dy).toInt().coerceIn(0, maxY)
                        try { windowManager?.updateViewLayout(overlay, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap?.invoke()
                    true
                }
                else -> true
            }
        }
    }

    /** Saat bot perlu mengetuk tepat di bawah bubble, bubble dibuat "tembus sentuh" sebentar. */
    private fun setOverlayTouchable(on: Boolean) {
        uiHandler.post {
            val p = overlayParams ?: return@post
            val v = overlay ?: return@post
            p.flags = if (on) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try { windowManager?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    private fun toggleEnabled() {
        enabled = !enabled
        if (enabled) {
            resetTask()
            nextAllowedAt = 0L
            tabTaps = 0
            tabTapsFor = null
            blockedUntil.clear()
            staleList.clear()
            lastCount.clear()
            emptyAtCount.clear()
            zeroTicks.clear()
            loadingTicks = 0
            refreshAfterDone = false
            emptyUntil.clear()
            waitingAll = false
            igDone = 0
            shotSeenTicks = 0
            pageLoadUntil = 0L
            stallSince = 0L
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
        statusView?.text = "$statusText\n✔ $doneCount selesai | IG batch ${igDone % TASKS_PER_BREAK}/$TASKS_PER_BREAK"
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
        stallMarked = false
        tickInner()
        if (!stallMarked) stallSince = 0L
    }

    private suspend fun tickInner() {
        val now = System.currentTimeMillis()
        val pkg = rootInActiveWindow?.packageName?.toString() ?: currentPkg
        if (pkg == packageName) return

        // Halaman baru di-refresh: tunggu selesai dimuat
        if (now < pageLoadUntil) {
            setStatus("Memuat ulang halaman…")
            return
        }
        // Link profil Instagram tidak jalan sekitar 28 detik -> refresh web
        if (linkWatchdog(now)) return

        val bmp = captureBitmap()
        if (bmp == null) {
            setStatus("Gagal potret layar (kode $lastShotError)")
            return
        }
        val all = ocr(bmp)
        bmp.recycle()
        val ov = overlayRect()
        val lines = all.filter { !ov.contains(it.rect.centerX(), it.rect.centerY()) }

        val appPlatform = platformOfPackage(pkg)
        if (appPlatform != null) {
            handleSocialApp(lines, now, appPlatform)
            return
        }
        val looksPr = lines.any {
            it.norm.contains("ikuti di instagram") || it.norm.contains("ikuti di tiktok") ||
                it.norm.contains("periksa follow saya") || it.norm.contains("buka profil dan ikuti") ||
                it.norm.contains("tugas saya") || it.norm.contains("jeda untuk menjaga") ||
                it.norm.contains("tugas sedang dikerjakan") || it.norm.contains("batalkan tugas") ||
                it.norm.contains("buka postingan") || it.norm.contains("pilih tempat bekerja")
        }
        if (looksPr) {
            if (pkg != null) browserPkg = pkg
            handlePr(lines, now)
        } else {
            setStatus("Halaman Tugas tidak terlihat (OCR ${lines.size} baris)")
            markStall(now, "Halaman Tugas tidak terlihat")
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

    // ---------------------------------------------------------------- Instagram

    private fun platformOfPackage(pkg: String?): Platform? = when {
        pkg == null -> null
        pkg == IG_PACKAGE -> Platform.INSTAGRAM
        pkg in TIKTOK_PACKAGES -> Platform.TIKTOK
        else -> null
    }

    /** Layar aplikasi Instagram / TikTok: buka profil target, tekan Ikuti, lalu kembali ke web. */
    private fun handleSocialApp(lines: List<OcrLine>, now: Long, platform: Platform) {
        val restricted = lines.any {
            it.norm.contains("coba lagi nanti") || it.norm.contains("try again later") ||
                it.norm.contains("tindakan diblokir") || it.norm.contains("action blocked") ||
                (platform == Platform.TIKTOK && (it.norm.contains("terlalu cepat") || it.norm.contains("too fast")))
        }
        if (restricted) {
            if (platform == Platform.INSTAGRAM) {
                enabled = false
                setStatus("⛔ Instagram membatasi akun. Bot dimatikan, istirahat dulu.")
                Toast.makeText(this, "Instagram membatasi aksi. Bot dimatikan.", Toast.LENGTH_LONG).show()
            } else {
                // TikTok dibatasi: istirahatkan TikTok 30 menit, batalkan tugas, lanjut Instagram
                if (now >= (blockedUntil[platform] ?: 0L)) blockedUntil[platform] = now + RESTRICT_BLOCK_MS
                staleList.add(platform)
                cancelAfterReturn = true
                returnToBrowser(now, "TikTok membatasi aksi, istirahat ${RESTRICT_BLOCK_MS / 60_000} menit")
            }
            return
        }

        val h = resources.displayMetrics.heightPixels
        val allWords = lines.flatMap { it.words }

        // Batas atas: di bawah baris statistik (postingan/pengikut)
        val statY = allWords
            .filter { it.norm == "postingan" || it.norm == "posts" || it.norm == "pengikut" || it.norm == "followers" }
            .filter { it.rect.centerY() < h * 0.30 }
            .maxOfOrNull { it.rect.centerY() } ?: (h * 0.15).toInt()
        val topLimit = statY + if (platform == Platform.TIKTOK) 25 else 40
        val statFound = allWords.any {
            (it.norm == "postingan" || it.norm == "posts" || it.norm == "pengikut" || it.norm == "followers") &&
                it.rect.centerY() < h * 0.30
        }

        // Batas bawah: sebelum "Disarankan untuk Anda" (di bawahnya ada tombol Ikuti akun lain!)
        val headingY = lines
            .filter { it.norm.contains("disarankan untuk") || it.norm.contains("suggested for") }
            .minOfOrNull { it.rect.top }
        val bottomLimit = headingY?.minus(10) ?: (h * 0.88).toInt()

        // Semua kata di area profil target (di bawah statistik, di atas "Disarankan")
        val zoneLines = lines.filter { l ->
            l.words.size <= 6 && l.rect.centerY() in topLimit until bottomLimit
        }

        // Baris yang HANYA berisi kata tombol (Ikuti, Kirim Pesan, Berlangganan, Kontak, dst).
        // Kalimat bio seperti "Ikuti kami di TikTok" tidak lolos.
        val strictLines = zoneLines.filter { l ->
            l.words.all { it.norm in BUTTON_WORDS || it.norm.length <= 2 || it.norm.startsWith("mengik") || isFollowingWord(it.norm, platform) }
        }

        // Jangkar: tombol milik profil ini selalu satu baris dengan tombol lain seperti Kirim Pesan.
        val anchorYs = zoneLines.flatMap { it.words }
            .filter { it.norm in ANCHOR_WORDS }
            .map { it.rect.centerY() }

        fun pick(pred: (OcrWord) -> Boolean): OcrWord? {
            // 1. satu baris dengan tombol jangkar (paling pasti)
            if (anchorYs.isNotEmpty()) {
                zoneLines.flatMap { it.words }
                    .filter { pred(it) && anchorYs.any { y -> abs(y - it.rect.centerY()) < 70 } }
                    .minByOrNull { it.rect.centerY() }
                    ?.let { return it }
            }
            // 2. baris yang isinya hanya kata tombol, ambil yang PALING ATAS
            strictLines.flatMap { it.words }
                .filter { pred(it) }
                .minByOrNull { it.rect.centerY() }
                ?.let { return it }
            // 3. cadangan terakhir: baris sangat pendek, paling atas
            return zoneLines.filter { it.words.size <= 3 }
                .flatMap { it.words }
                .filter { pred(it) }
                .minByOrNull { it.rect.centerY() }
        }

        val following = pick { isFollowingWord(it.norm, platform) }
        val followBtn = pick { it.norm in FOLLOW_WORDS }
        var followingRect: Rect? = following?.rect
        var followRect: Rect? = followBtn?.rect
        if (followingRect == null && followRect == null &&
            (phase == Phase.IN_INSTAGRAM || phase == Phase.IG_CLICKED)
        ) {
            // OCR tidak menemukan tombol (mis. teks putih di tombol biru): coba lewat pohon aksesibilitas
            val nodes = findButtonNodes(topLimit, bottomLimit)
            followRect = nodes.first
            followingRect = nodes.second
        }
        if (platform == Platform.TIKTOK && followingRect == null && followRect == null &&
            (phase == Phase.IN_INSTAGRAM || phase == Phase.IG_CLICKED)
        ) {
            // TikTok: setelah Ikuti, tombol kadang jadi hanya "Pesan" (atau "Diminta" yang tidak terbaca OCR).
            // Tombol Ikuti sudah hilang dan tombol Pesan muncul = berarti sudah mengikuti / permintaan terkirim.
            val minWait = if (phase == Phase.IG_CLICKED) 1200L else 5000L
            if (now - phaseSince >= minWait) {
                val msgBtn = strictLines.flatMap { it.words }
                    .filter { it.norm == "pesan" || it.norm == "message" }
                    .minByOrNull { it.rect.centerY() }
                if (msgBtn != null) followingRect = msgBtn.rect
            }
        }
        val followingOk = followingRect != null &&
            (tapY < 0 || abs(followingRect.centerY() - tapY) < 150)

        val hdrNow = profileHeader(lines, h, platform)
        val acc = acceptedProfileHdr
        var onTarget = headerMatchesTarget(lines, h, platform) || (acc != null && sameHdr(hdrNow, acc))
        if (!onTarget && phase == Phase.IN_INSTAGRAM && statFound && hdrNow.isNotEmpty() &&
            !sameHdr(hdrNow, lastProfileHdr) && (followRect != null || followingRect != null)
        ) {
            // Yang menentukan adalah LINK di misi, bukan username yang tertulis (bisa username lama, mis.
            // @transikasi padahal profilnya @transitira). Link selalu membuka akun yang benar, jadi terima
            // profil BARU (beda dari profil terakhir yang dikerjakan) yang sudah stabil sebentar.
            if (sameHdr(hdrNow, mismatchHdr)) {
                if (now - mismatchSince >= MISMATCH_ACCEPT_MS) {
                    acceptedProfileHdr = hdrNow
                    onTarget = true
                    setStatus("Profil dari link dibuka (nama beda dari @${targetUser ?: "?"}), lanjut")
                }
            } else {
                mismatchHdr = hdrNow
                mismatchSince = now
            }
        }

        when (phase) {
            Phase.IN_INSTAGRAM -> {
                if (!onTarget) {
                    if (now - phaseSince > PROFILE_TIMEOUT) {
                        retryProfile(now, "Profil bukan target")
                    } else {
                        setStatus("Menunggu profil @${targetUser ?: "?"}…")
                    }
                } else if (followingRect != null) {
                    verifiedFollow = true
                    lastProfileHdr = hdrNow
                    returnToBrowser(now, "Sudah mengikuti")
                } else if (followRect != null) {
                    if (platform == Platform.INSTAGRAM) {
                        // Khusus Instagram: jeda 2-3 detik dulu sebelum menekan Ikuti
                        if (igPreTapAt == 0L) {
                            igPreTapAt = now
                            igPreTapDelay = Random.nextLong(2000, 3000)
                        }
                        if (now - igPreTapAt < igPreTapDelay) {
                            setStatus("Tombol Ikuti ketemu, tunggu sebentar sebelum menekan…")
                            return
                        }
                        igPreTapAt = 0L
                    }
                    tap(followRect)
                    tapY = followRect.centerY()
                    phase = Phase.IG_CLICKED
                    phaseSince = now
                    igRetries = 0
                    setStatus("Menekan tombol Ikuti…")
                } else if (now - phaseSince > PROFILE_TIMEOUT) {
                    retryProfile(now, "Tombol Ikuti tidak ketemu")
                } else {
                    setStatus("Mencari tombol Ikuti… (percobaan ${igAttempts + 1}/$MAX_PROFILE_TRIES, OCR ${lines.size} baris)")
                }
            }
            Phase.IG_CLICKED -> {
                if (onTarget && followingOk) {
                    verifiedFollow = true
                    lastProfileHdr = hdrNow
                    returnToBrowser(now, "Berhasil mengikuti")
                } else if (now - phaseSince > 3500) {
                    if (onTarget && followRect != null && igRetries < 2) {
                        igRetries++
                        tap(followRect)
                        phaseSince = now
                    } else {
                        retryProfile(now, "Ikuti belum berhasil")
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

    /** Cocokkan nama akun di profil (Instagram: judul; TikTok: baris @username) dengan akun target. */
    private fun headerMatchesTarget(lines: List<OcrLine>, h: Int, platform: Platform): Boolean {
        val t = targetUser?.filter { it.isLetterOrDigit() } ?: return true
        if (t.length < 3) return true
        val hdr = profileHeader(lines, h, platform)
        if (hdr.isEmpty()) return true
        if (hdr.contains(t)) return true
        if (hdr.length >= 5 && t.contains(hdr)) return true
        var n = 0
        while (n < hdr.length && n < t.length && hdr[n] == t[n]) n++
        if (n >= minOf(6, t.length)) return true
        // Belum cocok: OCR bisa salah baca (l/I/1, O/0) atau judul tertutup. Cari username di area atas yang lebih luas.
        val wide = lines
            .filter { it.rect.centerY() in (h * 0.03).toInt()..(h * 0.50).toInt() }
            .joinToString("") { it.norm }
            .filter { it.isLetterOrDigit() }
        if (fuzzyContains(wide, t)) return true
        // Terakhir: teks asli aplikasi lewat pohon aksesibilitas (tidak bergantung pada OCR)
        return nodesContainTarget(t)
    }

    /** Teks judul profil (huruf/angka saja): Instagram = baris atas, TikTok = baris @username. */
    private fun profileHeader(lines: List<OcrLine>, h: Int, platform: Platform): String =
        if (platform == Platform.TIKTOK) {
            lines
                .filter { it.rect.centerY() in (h * 0.04).toInt()..(h * 0.20).toInt() && it.text.contains("@") }
                .joinToString("") { l -> l.text.substringAfter("@").lowercase() }
                .filter { it.isLetterOrDigit() }
        } else {
            lines
                .filter { it.rect.centerY() in (h * 0.04).toInt()..(h * 0.12).toInt() }
                .joinToString("") { it.norm }
                .filter { it.isLetterOrDigit() }
        }

    private fun sameHdr(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val x = ocrNorm(a)
        val y = ocrNorm(b)
        return x == y || (minOf(x.length, y.length) >= 5 && (x.contains(y) || y.contains(x)))
    }

    /** Kata status "sudah mengikuti": Mengikuti, Diminta, Requested, Teman (TikTok), termasuk salah baca OCR. */
    private fun isFollowingWord(norm: String, platform: Platform): Boolean {
        if (norm.isEmpty()) return false
        if (norm.startsWith("mengik") || norm in FOLLOWING_WORDS) return true
        if (platform == Platform.TIKTOK && norm in TIKTOK_FRIEND_WORDS) return true
        if (norm.startsWith("dimint") || norm.startsWith("request")) return true
        return norm.length in 6..8 && ocrNorm(norm) == ocrNorm("diminta")
    }

    /** Samakan huruf yang sering tertukar oleh OCR. */
    private fun ocrNorm(x: String): String = buildString {
        for (c in x.lowercase()) {
            append(
                when (c) {
                    'i', 'l', '1', '|', '!' -> 'l'
                    'o', '0', 'q' -> 'o'
                    '5' -> 's'
                    '8' -> 'b'
                    '2' -> 'z'
                    else -> c
                }
            )
        }
    }

    /** Apakah [target] ada di dalam [text], dengan toleransi 1 salah huruf untuk nama panjang (>= 8 huruf). */
    private fun fuzzyContains(text: String, target: String): Boolean {
        if (target.length < 3 || text.isEmpty()) return false
        val a = ocrNorm(target)
        val b = ocrNorm(text)
        if (b.contains(a)) return true
        val tol = if (a.length >= 8) a.length / 8 else 0
        if (tol == 0) return false
        // jarak edit terkecil antara target dan potongan mana pun dari text
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return (prev.minOrNull() ?: Int.MAX_VALUE) <= tol
    }

    private fun walkNodes(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = mutableListOf(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000) {
            val node = stack.removeAt(stack.lastIndex)
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
    }

    private fun nodesContainTarget(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var found = false
        walkNodes(root) { n ->
            if (!found) {
                val txt = ((n.text?.toString() ?: "") + (n.contentDescription?.toString() ?: ""))
                    .lowercase().filter { it.isLetterOrDigit() }
                if (txt.isNotEmpty() && fuzzyContains(txt, t)) found = true
            }
        }
        return found
    }

    /** Cari tombol Ikuti / Mengikuti lewat pohon aksesibilitas, di area profil. Pair(tombolIkuti, tombolMengikuti). */
    private fun findButtonNodes(topLimit: Int, bottomLimit: Int): Pair<Rect?, Rect?> {
        val root = rootInActiveWindow ?: return Pair(null, null)
        val follows = mutableListOf<Rect>()
        val followings = mutableListOf<Rect>()
        walkNodes(root) { n ->
            val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
            if (label.isNotEmpty() && label.length <= 14) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty && n.isVisibleToUser && r.centerY() >= topLimit && r.centerY() < bottomLimit) {
                    if (label in FOLLOW_WORDS || label == "ikuti balik" || label == "follow back") {
                        follows.add(r)
                    } else if (label.startsWith("mengikuti") || label.startsWith("following") ||
                        label == "diminta" || label == "requested"
                    ) {
                        followings.add(r)
                    }
                }
            }
        }
        return Pair(follows.minByOrNull { it.top }, followings.minByOrNull { it.top })
    }

    /** Ambil @username target dari jendela tugas PR.SOCIAL. */
    private fun parseTarget(lines: List<OcrLine>): String? {
        // Lencana "Sedang dikerjakan" di jendela tugas (bukan banner "Tugas sedang dikerjakan")
        val badge = lines.firstOrNull {
            it.norm.contains("sedang dikerjakan") && !it.norm.contains("tugas sedang")
        }
        val words = lines.flatMap { it.words }
        val w = (if (badge != null) {
            words.firstOrNull {
                it.raw.startsWith("@") && abs(it.rect.centerY() - badge.rect.centerY()) < 70
            }
        } else null)
            // cadangan: baris banner "Ikuti di Instagram · @akun · dimulai ..."
            ?: lines.firstOrNull { it.norm.contains("dimulai") }
                ?.words?.firstOrNull { it.raw.startsWith("@") }
            ?: return null
        val name = w.raw.removePrefix("@").lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        return name.ifEmpty { null }
    }

    private fun returnToBrowser(now: Long, msg: String) {
        igPreTapAt = 0L
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg, kembali ke halaman tugas")
    }

    /** Gagal menemukan/menekan Ikuti: kembali ke web lalu buka profil lagi (bukan menekan Periksa). */
    private fun retryProfile(now: Long, msg: String) {
        igPreTapAt = 0L
        igAttempts++
        verifiedFollow = false
        tapY = -1
        cancelAfterReturn = igAttempts >= MAX_PROFILE_TRIES
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg. Ulangi buka profil ($igAttempts/$MAX_PROFILE_TRIES)")
    }

    // ---------------------------------------------------------------- Halaman tugas

    private fun handlePr(lines: List<OcrLine>, now: Long) {
        val check = lines.firstOrNull { it.norm.contains("periksa follow saya") }
        val reviewed = lines.any { it.norm.contains("ditinjau") }
        val startBtn = lines.firstOrNull {
            it.norm.startsWith("mulai") && !it.norm.contains("dalam") && it.norm.any { c -> c.isDigit() }
        }
        val confirmMark = lines.any { it.norm.contains("konfirmasi follow anda") }
        val modalOpen = check != null || startBtn != null || confirmMark || reviewed

        if (phase == Phase.CHECKING && (check == null || reviewed)) {
            onTaskDone(now)
            return
        }

        // Satu misi selesai -> pasti refresh web (tidak tergantung ada/tidaknya tombol "Tugas berikutnya")
        if (refreshAfterDone) {
            if (!canStartNew(now)) return
            refreshAfterDone = false
            doRefresh(now, "Tugas selesai")
            return
        }

        // Sheet daftar platform terbuka (salah ketuk tab "+N"): pilih platform dari situ / tutup
        if (lines.any { it.norm.contains("pilih tempat bekerja") }) {
            handlePlatformSheet(lines, now)
            return
        }
        sheetTries = 0

        // Misi like/suka/komentar (bukan follow) tidak dikerjakan: butuh screenshot, bot tidak bisa.
        // Kalau terbuka/terlanjur "sedang dikerjakan": batalkan, lalu refresh.
        if (check == null && isNonFollowModal(lines)) {
            handleNonFollowTask(lines, now)
            return
        }
        likeCancelTries = 0

        // Misi yang minta screenshot tidak bisa dikerjakan bot -> batalkan + refresh web
        // (modal "Mulai" memang memuat teks tangkapan layar, jadi diabaikan; harus bertahan 3 tick)
        if (check == null && startBtn == null && isScreenshotMission(lines, confirmMark)) {
            shotSeenTicks++
            if (shotSeenTicks >= 3) {
                shotSeenTicks = 0
                handleScreenshotMission(now)
            } else {
                setStatus("Memeriksa jenis misi…")
            }
            return
        }
        shotSeenTicks = 0

        if (check == null && startBtn != null) {
            if (!canStartNew(now)) return
            tap(startBtn.rect)
            phase = Phase.LIST
            phaseSince = now
            setStatus("Menekan Mulai…")
            return
        }

        // Tombol "Tugas berikutnya" TIDAK dipakai: situs bisa memilihkan misi like/suka.
        // Selesai satu tugas -> refresh web, lalu bot memilih sendiri misi follow dari daftar.
        val nextBtn = lines.firstOrNull { it.norm.contains("tugas berikutnya") }
        if (check == null && nextBtn != null) {
            if (!canStartNew(now)) return
            doRefresh(now, "Tugas selesai, kembali ke daftar")
            return
        }

        if (!modalOpen) {
            if (phase == Phase.LIST) {
                val h = resources.displayMetrics.heightPixels
                // 1. Ada "Tugas sedang dikerjakan": kerjakan yang itu dulu (prioritas utama)
                if (resumeInProgress(lines, now)) return
                // 2. Banner jeda dari situs (batas 50 misi): tandai platform itu, tambah jeda 5-10 menit
                val pause = findSitePause(lines, shownPlatform(lines, h))
                if (pause != null && now >= (blockedUntil[pause.first] ?: 0L)) {
                    // Jeda tambahan 5-10 menit setelah jeda situs selesai: khusus Instagram
                    val extra = if (pause.first == Platform.INSTAGRAM) {
                        Random.nextLong(EXTRA_PAUSE_MIN_MS, EXTRA_PAUSE_MAX_MS)
                    } else 0L
                    blockedUntil[pause.first] = now + pause.second + extra
                    staleList.add(pause.first)
                    setStatus(
                        "Jeda situs ${pause.first.label}: ${fmtDur(pause.second)}" +
                            if (extra > 0) " + ${fmtDur(extra)} tambahan" else ""
                    )
                    return
                }
                // Baca angka di tab: tab berangka 0 = tidak ada misi, jangan dikunjungi (tidak "memaksa" cari)
                val loadingNow = isLoadingOrError(lines)
                for ((pl, c) in tabCounts(lines)) {
                    lastCount[pl] = c
                    val until = emptyUntil[pl]
                    if (c == 0) {
                        // angka 0 saat halaman masih memuat itu belum benar-benar "habis": tunggu stabil beberapa detik
                        val z = (zeroTicks[pl] ?: 0) + 1
                        zeroTicks[pl] = z
                        if (z >= 4 && !loadingNow) {
                            if (until == null || now >= until) emptyUntil[pl] = now + emptyRetry(pl)
                            emptyAtCount[pl] = 0
                        }
                    } else {
                        zeroTicks[pl] = 0
                        val atCount = emptyAtCount[pl] ?: Int.MAX_VALUE
                        if (until != null && c > atCount &&
                            (atCount == 0 || now >= until - emptyRetry(pl) + 60_000L)
                        ) {
                            emptyUntil.remove(pl)   // ada misi baru muncul
                        }
                    }
                }
                // 3. Pilih platform: TikTok dulu, lalu Instagram (yang tidak jeda / tidak habis)
                val want = choosePlatform(now)
                if (want == null) {
                    waitingAll = true
                    val wait = PRIORITY.minOf { maxOf(blockedUntil[it] ?: 0L, emptyUntil[it] ?: 0L) } - now
                    if (now - lastRefreshAt >= WAIT_REFRESH_MS) {
                        // jangan menunggu dengan daftar basi: refresh berkala supaya angka tab & misi terbaru terbaca
                        doRefresh(now, "Cek ulang misi saat menunggu")
                        return
                    }
                    setStatus("TikTok & Instagram jeda/habis. Tunggu ${fmtDur(wait)}…")
                    return
                }
                if (waitingAll) {
                    waitingAll = false
                    staleList.remove(want)
                    doRefresh(now, "Jeda selesai")
                    return
                }
                // Habis jeda: refresh dulu supaya daftar misi segar (misi siapa cepat dia dapat)
                if (staleList.remove(want)) {
                    doRefresh(now, "Jeda ${want.label} selesai")
                    return
                }
                // 4. Klik logo platform dulu (setelah refresh halaman kembali ke "Semua")
                if (selectPlatformTab(lines, now, want)) return
                // 5. Baru ambil tugas baru
                listStep(lines, now, want)
            } else if (now - phaseSince > 15000) {
                doRefresh(now, "Halaman tugas macet")
            } else {
                setStatus("Menunggu halaman tugas…")
            }
            return
        }

        if (check == null) {
            // modal terbuka tapi belum jelas, tunggu (kalau kelamaan -> refresh)
            setStatus("Menunggu jendela tugas…")
            markStall(now, "Jendela tugas tidak muncul")
            return
        }

        when (phase) {
            Phase.LIST -> {
                if (!canStartNew(now)) return
                targetUser = parseTarget(lines)
                taskPlatform = detectTaskPlatform(lines) ?: choosePlatform(now) ?: taskPlatform
                tapLink(lines)
                phase = Phase.IN_INSTAGRAM
                phaseSince = now
                linkWaitSince = now
                resumeTaps = 0
                checkAttempts = 0
                igRetries = 0
                igAttempts = 0
                verifiedFollow = false
                cancelAfterReturn = false
                tapY = -1
                setStatus("Membuka profil ${taskPlatform.label}…")
            }
            Phase.IN_INSTAGRAM -> {
                // Masih di halaman tugas: link belum membuka Instagram
                if (now - phaseSince > 8000) {
                    igAttempts++
                    if (igAttempts >= MAX_PROFILE_TRIES) {
                        cancelTask(lines, now)
                    } else {
                        tapLink(lines)
                        phaseSince = now
                        setStatus("Link belum terbuka, coba lagi ($igAttempts/$MAX_PROFILE_TRIES)")
                    }
                }
            }
            Phase.IG_CLICKED, Phase.RETURNING -> {
                if (cancelAfterReturn) {
                    cancelTask(lines, now)
                } else if (!verifiedFollow) {
                    tapLink(lines)
                    phase = Phase.IN_INSTAGRAM
                    phaseSince = now
                    setStatus("Membuka ulang profil Instagram…")
                } else {
                    tap(check.rect)
                    phase = Phase.CHECKING
                    phaseSince = now
                    checkAttempts = 1
                    setStatus("Follow terverifikasi, menekan periksa…")
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

    private fun listStep(lines: List<OcrLine>, now: Long, platform: Platform) {
        if (!canStartNew(now)) return
        val h = resources.displayMetrics.heightPixels
        val row = lines
            .filter {
                it.norm.contains(platform.rowKey) && !it.norm.contains("suka") &&
                    !it.norm.contains("dimulai") && !it.norm.contains("dikerjakan") &&
                    it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.85
            }
            .minByOrNull { it.rect.top }
        if (row == null) {
            if (isLoadingOrError(lines)) {
                // "Memuat tugas…" / "Tidak dapat memuat": BUKAN misi habis. Jangan tandai habis; refresh kalau kelamaan.
                loadingTicks++
                setStatus("Halaman tugas belum termuat ($loadingTicks)…")
                if (loadingTicks >= 12) {
                    loadingTicks = 0
                    doRefresh(now, "Halaman tugas tidak termuat")
                }
                return
            }
            loadingTicks = 0
            idleTicks++
            setStatus("Tidak ada tugas Follow ${platform.label} terlihat (OCR ${lines.size} baris)")
            if (idleTicks >= 5) {
                // Misi follow platform ini habis: refresh web, lalu pilih platform berikutnya
                idleTicks = 0
                emptyUntil[platform] = now + emptyRetry(platform)
                emptyAtCount[platform] = lastCount[platform] ?: Int.MAX_VALUE
                doRefresh(now, "Misi follow ${platform.label} habis")
            }
            return
        }
        idleTicks = 0
        tap(row.rect)
        phaseSince = now
        setStatus("Membuka tugas…")
    }

    private fun tapLink(lines: List<OcrLine>) {
        val link = lines.firstOrNull { it.norm.contains(taskPlatform.domain) }
            ?: lines.firstOrNull { it.norm.contains("buka profil dan ikuti") }
        if (link != null) tap(link.rect)
    }

    private fun canStartNew(now: Long): Boolean {
        if (now < nextAllowedAt) {
            setStatus("Jeda ${fmtDur(nextAllowedAt - now)}…")
            return false
        }
        return true
    }

    private fun onTaskDone(now: Long) {
        doneCount++
        val donePlatform = taskPlatform
        resetTask()
        refreshAfterDone = true
        nextAllowedAt = now + Random.nextLong(3000, 5000)
        if (donePlatform == Platform.INSTAGRAM && ++igDone % TASKS_PER_BREAK == 0) {
            // Khusus Instagram: tiap 10 misi selesai, Instagram istirahat 3-5 menit (TikTok tidak ikut jeda)
            val ms = Random.nextLong(BREAK_MIN_MS, BREAK_MAX_MS)
            blockedUntil[Platform.INSTAGRAM] = now + ms
            staleList.add(Platform.INSTAGRAM)
            setStatus("☕ $igDone misi Instagram selesai. Instagram istirahat ${fmtDur(ms)}…")
        } else {
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
        igRetries = 0
        igAttempts = 0
        verifiedFollow = false
        cancelAfterReturn = false
        tapY = -1
        linkWaitSince = 0L
        igPreTapAt = 0L
        taskRefreshes = 0
        acceptedProfileHdr = null
        mismatchHdr = ""
    }

    private fun fmtDur(ms: Long): String {
        val sec = (maxOf(ms, 0L) + 999) / 1000
        return when {
            sec >= 3600 -> "${sec / 3600}j ${(sec % 3600) / 60}m"
            sec >= 60 -> "${sec / 60}m ${sec % 60}d"
            else -> "${sec}d"
        }
    }

    // ---------------------------------------------------------------- Refresh, tab, prioritas

    /** Refresh halaman web. Setelah refresh halaman balik ke tab "Semua", jadi logo platform diklik lagi. */
    private fun doRefresh(now: Long, msg: String) {
        val keep = taskRefreshes
        resetTask()
        taskRefreshes = keep
        tabTaps = 0
        tabTapsFor = null
        idleTicks = 0
        loadingTicks = 0
        zeroTicks.clear()
        waitingAll = false
        resumeTaps = 0
        stallSince = 0L
        lastRefreshAt = now
        pageLoadUntil = now + PAGE_LOAD_WAIT
        setStatus("$msg → refresh halaman…")
        refreshPage()
    }

    /** Bot macet di satu keadaan terlalu lama -> refresh web. */
    private fun markStall(now: Long, why: String): Boolean {
        stallMarked = true
        if (stallSince == 0L) stallSince = now
        if (now - stallSince >= STALL_REFRESH_TIMEOUT) {
            stallSince = 0L
            doRefresh(now, why)
            return true
        }
        return false
    }

    /** Link profil IG tidak jalan ~28 detik sejak tugas dibuka -> refresh (setelah 3x refresh: batalkan tugas). */
    private fun linkWatchdog(now: Long): Boolean {
        if (linkWaitSince == 0L || verifiedFollow) return false
        if (phase != Phase.IN_INSTAGRAM && phase != Phase.IG_CLICKED && phase != Phase.RETURNING) return false
        if (now - linkWaitSince < LINK_REFRESH_TIMEOUT) return false
        linkWaitSince = 0L
        if (taskRefreshes >= MAX_TASK_REFRESH) {
            cancelAfterReturn = true
            phase = Phase.RETURNING
            phaseSince = now
            launchBrowser()
            setStatus("Link tetap tidak jalan setelah $taskRefreshes× refresh, tugas dibatalkan")
        } else {
            taskRefreshes++
            doRefresh(now, "Link profil tidak jalan ${LINK_REFRESH_TIMEOUT / 1000} detik")
        }
        return true
    }

    /** Platform yang sedang ditampilkan di daftar tugas (dari judul daftar, cadangan: nama di baris tugas). */
    private fun shownPlatform(lines: List<OcrLine>, h: Int): Platform? {
        if (lines.any { it.norm == "semua platform" }) return null   // tab "Semua" masih terpilih
        val tl = lines.firstOrNull {
            Regex("^\\d+ tugas").containsMatchIn(it.norm) && it.norm.contains("@")
        }
        if (tl != null) {
            val head = lines
                .filter {
                    (it.norm == "tiktok" || it.norm == "instagram") &&
                        it.rect.bottom <= tl.rect.top + 15 && tl.rect.top - it.rect.bottom < 100
                }
                .maxByOrNull { it.rect.bottom }
            if (head != null) return if (head.norm == "tiktok") Platform.TIKTOK else Platform.INSTAGRAM
        }
        val rows = lines.filter {
            it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.92 &&
                !it.norm.contains("dimulai") && !it.norm.contains("dikerjakan")
        }
        val ig = rows.count { it.norm.contains("di instagram") }
        val tk = rows.count { it.norm.contains("di tiktok") }
        return when {
            tk > 0 && ig == 0 -> Platform.TIKTOK
            ig > 0 && tk == 0 -> Platform.INSTAGRAM
            else -> null
        }
    }

    /** Halaman tugas sedang memuat atau menampilkan pesan gagal memuat. */
    private fun isLoadingOrError(lines: List<OcrLine>): Boolean = lines.any {
        val n = it.norm
        n.contains("memuat") || n.contains("tidak dapat memuat") || n.contains("gagal memuat") ||
            n.contains("terjadi kesalahan") || n.contains("tidak ada koneksi") || n.contains("coba lagi")
    }

    private fun emptyRetry(p: Platform): Long =
        if (p == Platform.TIKTOK) EMPTY_RETRY_TIKTOK_MS else EMPTY_RETRY_IG_MS

    /** Angka jumlah misi di tab Instagram / TikTok (posisi tab dihitung dari geometri, sama seperti klik tab). */
    private fun tabCounts(lines: List<OcrLine>): Map<Platform, Int> {
        val semua = lines.filter { it.norm == "semua" }.minByOrNull { it.rect.top } ?: return emptyMap()
        val w = resources.displayMetrics.widthPixels
        val row = lines.filter {
            abs(it.rect.centerY() - semua.rect.centerY()) < 45 && it.rect.centerX() > semua.rect.centerX()
        }
        val plus = row.filter { it.norm.startsWith("+") }.maxByOrNull { it.rect.centerX() } ?: return emptyMap()
        val sc = w / 1080f
        fun spacing(t: Int) = (w - 60f * sc - 12f * sc * (t - 1)) / t + 12f * sc
        val span = (plus.rect.centerX() - semua.rect.centerX()).toFloat()
        val tiles = (3..8).minByOrNull { abs(span - (it - 1) * spacing(it)) } ?: return emptyMap()
        val d = spacing(tiles)
        val out = mutableMapOf<Platform, Int>()
        for ((idx, p) in listOf(Platform.INSTAGRAM, Platform.TIKTOK).withIndex()) {
            if (idx + 1 >= tiles - 1) continue
            val cx = semua.rect.centerX() + d * (idx + 1)
            val item = row
                .filter { !it.norm.startsWith("+") && abs(it.rect.centerX() - cx) < d / 2 - 12 }
                .minByOrNull { abs(it.rect.centerX() - cx) } ?: continue
            val n = item.norm
            val v = when {
                n == "o" || n == "0" -> 0
                n.any { it.isDigit() } -> n.filter { it.isDigit() }.toIntOrNull()
                else -> null
            } ?: continue
            out[p] = v
        }
        return out
    }

    /** Prioritas: TikTok dulu, lalu Instagram; lewati yang sedang jeda atau misinya habis. */
    private fun choosePlatform(now: Long): Platform? =
        PRIORITY.firstOrNull { now >= (blockedUntil[it] ?: 0L) && now >= (emptyUntil[it] ?: 0L) }

    private fun detectTaskPlatform(lines: List<OcrLine>): Platform? {
        if (lines.any { it.norm.contains("tiktok.com") }) return Platform.TIKTOK
        if (lines.any { it.norm.contains("instagram.com") }) return Platform.INSTAGRAM
        val tk = lines.any { it.norm.contains("di tiktok") }
        val ig = lines.any { it.norm.contains("di instagram") }
        return when {
            tk && !ig -> Platform.TIKTOK
            ig && !tk -> Platform.INSTAGRAM
            else -> null
        }
    }

    /** Banner situs "Jeda untuk menjaga akun Anda tetap aman" -> (platform, lama jeda dalam ms). */
    private fun findSitePause(lines: List<OcrLine>, shown: Platform?): Pair<Platform, Long>? {
        val b = lines.firstOrNull { it.norm.contains("jeda untuk menjaga") } ?: return null
        val text = lines
            .filter { it.rect.centerY() >= b.rect.top - 20 && it.rect.centerY() <= b.rect.bottom + 180 }
            .joinToString(" ") { it.norm }
        val otherType = (text.contains("suka") || text.contains("komentar") || text.contains("like")) &&
            !text.contains("follow") && !text.contains("ikuti")
        if (otherType) return null
        val platform = when {
            text.contains("tiktok") -> Platform.TIKTOK
            text.contains("instagram") -> Platform.INSTAGRAM
            else -> shown ?: return null
        }
        var ms = 0L
        Regex("(\\d+)\\s*(jam|j)\\b").find(text)?.let { ms += it.groupValues[1].toLong() * 3_600_000L }
        Regex("(\\d+)\\s*(menit|mnt)").find(text)?.let { ms += it.groupValues[1].toLong() * 60_000L }
        Regex("(\\d+)\\s*(detik|dtk)").find(text)?.let { ms += it.groupValues[1].toLong() * 1_000L }
        if (ms == 0L) ms = 15 * 60_000L
        return platform to ms
    }

    /** Klik logo platform (urutan tab: Semua, Instagram, TikTok, ...). true = masih sibuk di langkah ini. */
    private fun selectPlatformTab(lines: List<OcrLine>, now: Long, want: Platform): Boolean {
        val h = resources.displayMetrics.heightPixels
        if (shownPlatform(lines, h) == want) {
            tabTaps = 0
            tabTapsFor = null
            return false
        }
        if (tabTapsFor != want) {
            tabTapsFor = want
            tabTaps = 0
        }
        if (tabTaps >= 3) return false
        if (now - lastTabTapAt < 1800) {
            setStatus("Menunggu tab ${want.label} terbuka…")
            return true
        }
        val semua = lines.filter { it.norm == "semua" }.minByOrNull { it.rect.top }
        if (semua == null) {
            tabTaps++
            setStatus("Mencari tab ${want.label}…")
            return true
        }
        val w = resources.displayMetrics.widthPixels
        val idx = if (want == Platform.INSTAGRAM) 0 else 1   // urutan tab: Semua, Instagram, TikTok, ..., +N
        val rowItems = lines.filter {
            abs(it.rect.centerY() - semua.rect.centerY()) < 45 && it.rect.centerX() > semua.rect.centerX()
        }
        val plus = rowItems.filter { it.norm.startsWith("+") }.maxByOrNull { it.rect.centerX() }
        var target: Rect? = null
        if (plus != null) {
            // Lebar semua tab sama rata, jadi jarak "Semua" -> "+N" menunjukkan jumlah tab.
            // Posisi tab dihitung dari situ (tidak bergantung pada angka yang terbaca OCR, mis. "0" atau ikon jam).
            val sc = w / 1080f
            fun spacing(t: Int) = (w - 60f * sc - 12f * sc * (t - 1)) / t + 12f * sc
            val span = (plus.rect.centerX() - semua.rect.centerX()).toFloat()
            val tiles = (3..8).minByOrNull { abs(span - (it - 1) * spacing(it)) } ?: 4
            if (idx + 1 >= tiles - 1) {
                tabTaps = 3
                setStatus("Tab ${want.label} tidak ada")
                return false
            }
            val cx = (semua.rect.centerX() + spacing(tiles) * (idx + 1)).toInt()
            val cy = semua.rect.centerY()
            target = Rect(cx - 10, cy - 10, cx + 10, cy + 10)
        } else {
            val items = rowItems.filter { !it.norm.startsWith("+") }.sortedBy { it.rect.centerX() }
            if (items.size > idx) target = items[idx].rect
        }
        if (target == null) {
            tabTaps++
            setStatus("Tab ${want.label} tidak terlihat…")
            return true
        }
        tap(target)
        tabTaps++
        lastTabTapAt = now
        setStatus("Klik logo ${want.label} dulu ($tabTaps/3)…")
        return true
    }

    /** Banner "Tugas sedang dikerjakan" + tombol Lanjutkan: dahulukan tugas itu. true = sedang diurus. */
    private fun resumeInProgress(lines: List<OcrLine>, now: Long): Boolean {
        val banner = lines.firstOrNull { it.norm.contains("tugas sedang dikerjakan") } ?: return false
        val btn = lines
            .filter { it.norm.contains("lanjutkan") && abs(it.rect.centerY() - banner.rect.centerY()) < 160 }
            .minByOrNull { abs(it.rect.centerY() - banner.rect.centerY()) }
            ?: banner
        if (!canStartNew(now)) return true
        if (now - lastResumeAt < 3000) {
            setStatus("Menunggu tugas yang sedang dikerjakan terbuka…")
            return true
        }
        if (resumeTaps >= 4) {
            resumeTaps = 0
            doRefresh(now, "Tombol Lanjutkan tidak merespons")
            return true
        }
        resumeTaps++
        lastResumeAt = now
        tap(btn.rect)
        setStatus("Ada tugas sedang dikerjakan, dilanjutkan dulu ($resumeTaps/4)…")
        return true
    }

    private fun isScreenshotMission(lines: List<OcrLine>, confirmMark: Boolean): Boolean {
        // Misi Follow biasa menulis "Tangkapan layar tidak diperlukan" -> bukan misi screenshot
        if (confirmMark) return false
        val modalHint = lines.any {
            it.norm.contains("batalkan tugas") || it.norm.contains("tautan tidak berfungsi") ||
                it.norm.contains("bayaran")
        }
        if (!modalHint) return false
        return lines.any {
            val n = it.norm
            (n.contains("tangkapan layar") || n.contains("screenshot") || n.contains("unggah bukti") ||
                n.contains("upload bukti") || n.contains("kirim bukti")) &&
                !n.contains("tidak") && !n.contains("diperlukan") && !n.contains("not required")
        }
    }

    private fun isNonFollowModal(lines: List<OcrLine>): Boolean {
        if (lines.any { it.norm.contains("buka profil dan ikuti") }) return false
        if (lines.any { it.norm.contains("buka postingan") }) return true
        val modalHint = lines.any {
            it.norm.contains("batalkan tugas") || it.norm.contains("tautan tidak berfungsi")
        }
        return modalHint && lines.any {
            it.norm.startsWith("beri suka") || it.norm.startsWith("beri komentar") ||
                it.norm.startsWith("komentar di") || it.norm.startsWith("bagikan di")
        }
    }

    private fun handleNonFollowTask(lines: List<OcrLine>, now: Long) {
        val cancel = lines.firstOrNull { it.norm.contains("batalkan tugas") }
        if (cancel != null && likeCancelTries < 2) {
            if (now - lastLikeCancelAt < 3000) {
                setStatus("Menunggu pembatalan misi like/suka…")
                return
            }
            tap(cancel.rect)
            lastLikeCancelAt = now
            likeCancelTries++
            setStatus("Misi like/suka tidak dikerjakan, dibatalkan ($likeCancelTries/2)…")
        } else {
            likeCancelTries = 0
            doRefresh(now, "Misi like/suka tidak dikerjakan")
        }
    }

    /** Sheet "Platform - Pilih tempat bekerja" (terbuka kalau tab "+N" terketuk): pilih platform dari situ, atau tutup. */
    private fun handlePlatformSheet(lines: List<OcrLine>, now: Long) {
        if (now - lastTabTapAt < 1800) {
            setStatus("Menunggu daftar platform menutup…")
            return
        }
        lastTabTapAt = now
        val want = choosePlatform(now) ?: Platform.INSTAGRAM
        val h = resources.displayMetrics.heightPixels
        val label = lines.firstOrNull { it.norm == want.label.lowercase() && it.rect.centerY() > h * 0.3 }
        if (label != null && sheetTries < 3) {
            sheetTries++
            tap(label.rect)
            setStatus("Memilih ${want.label} dari daftar platform…")
        } else {
            sheetTries = 0
            performGlobalAction(GLOBAL_ACTION_BACK)
            setStatus("Menutup daftar platform…")
        }
    }

    /** Misi minta screenshot (biasanya muncul kalau klik profil >5x): cukup refresh web.
     *  Misinya akan muncul lagi di banner "Tugas sedang dikerjakan" dan dilanjutkan lewat Lanjutkan. */
    private fun handleScreenshotMission(now: Long) {
        doRefresh(now, "Misi minta screenshot")
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

    private fun refreshPage() {
        val pkg = browserPkg ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(TASKS_URL))
                .setPackage(pkg)
                // pakai ulang tab yang sama (tidak menumpuk tab baru) di Chrome
                .putExtra("com.android.browser.application_id", packageName)
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
        if (overlayRect().contains(r.centerX(), r.centerY())) {
            // titik ketuk tertutup bubble: tembuskan sentuhan sebentar supaya tidak malah menekan bubble
            setOverlayTouchable(false)
            uiHandler.postDelayed({
                dispatchGesture(gesture, null, null)
                uiHandler.postDelayed({ setOverlayTouchable(true) }, 400)
            }, 150)
            return true
        }
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val IG_PACKAGE = "com.instagram.android"
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"
        )
        private val PRIORITY = listOf(Platform.TIKTOK, Platform.INSTAGRAM)   // setelah "Tugas sedang dikerjakan"
        private const val TASKS_PER_BREAK = 10               // tiap 10 misi selesai -> istirahat
        private const val BREAK_MIN_MS = 180_000L            // 3 menit
        private const val BREAK_MAX_MS = 300_000L            // 5 menit
        private const val EXTRA_PAUSE_MIN_MS = 300_000L      // tambahan setelah jeda situs selesai: 5 menit
        private const val EXTRA_PAUSE_MAX_MS = 600_000L      // ... sampai 10 menit
        private const val WAIT_REFRESH_MS = 60_000L         // saat semua platform jeda/habis: cek ulang tiap 1 menit
        private const val EMPTY_RETRY_IG_MS = 120_000L       // misi Instagram habis -> cek lagi 2 menit kemudian
        private const val EMPTY_RETRY_TIKTOK_MS = 300_000L   // misi TikTok habis -> cek lagi 5 menit kemudian
        private const val RESTRICT_BLOCK_MS = 1_800_000L     // TikTok membatasi aksi -> istirahat 30 menit
        private val TIKTOK_FRIEND_WORDS = setOf("teman", "friends")
        private const val MAX_PROFILE_TRIES = 5
        private const val MISMATCH_ACCEPT_MS = 2_500L   // profil baru dari link: terima kalau stabil 2,5 detik
        private const val PROFILE_TIMEOUT = 25_000L
        private const val LINK_REFRESH_TIMEOUT = 28_000L   // link profil IG tidak jalan -> refresh web
        private const val STALL_REFRESH_TIMEOUT = 25_000L  // bot macet -> refresh web
        private const val PAGE_LOAD_WAIT = 4_500L
        private const val MAX_TASK_REFRESH = 3
        private const val TASKS_URL = "https://pr.social/tasks"
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
