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
 * Bot otomatis tugas "Ikuti di Instagram" di PR.SOCIAL.
 * Cara kerja: potret layar -> baca tulisan (OCR, di HP, tanpa internet) -> ketuk posisi tulisan.
 * Tidak bergantung pada browser mana pun.
 */
class AutomationService : AccessibilityService() {

    private enum class Phase { LIST, IN_INSTAGRAM, IG_CLICKED, RETURNING, CHECKING }

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
    private var needIgTab = true
    private var igTabTaps = 0
    private var lastTabTapAt = 0L
    private var resumeTaps = 0
    private var lastResumeAt = 0L
    private var linkWaitSince = 0L
    private var taskRefreshes = 0
    private var pageLoadUntil = 0L
    private var stallSince = 0L
    private var stallMarked = false
    private val doneTimes = ArrayList<Long>()
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
            needIgTab = true
            igTabTaps = 0
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
        statusView?.text = "$statusText\n✔ $doneCount selesai | ${recentCount(System.currentTimeMillis())}/$MAX_PER_HOUR per jam"
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

        if (pkg == IG_PACKAGE) {
            handleInstagram(lines, now)
            return
        }
        val looksPr = lines.any {
            it.norm.contains("ikuti di instagram") || it.norm.contains("periksa follow saya") ||
                it.norm.contains("buka profil dan ikuti") || it.norm.contains("tugas saya")
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

    private fun handleInstagram(lines: List<OcrLine>, now: Long) {
        if (lines.any {
                it.norm.contains("coba lagi nanti") || it.norm.contains("try again later") ||
                    it.norm.contains("tindakan diblokir") || it.norm.contains("action blocked")
            }
        ) {
            enabled = false
            setStatus("⛔ Instagram membatasi akun. Bot dimatikan, istirahat dulu.")
            Toast.makeText(this, "Instagram membatasi aksi. Bot dimatikan.", Toast.LENGTH_LONG).show()
            return
        }

        val h = resources.displayMetrics.heightPixels
        val allWords = lines.flatMap { it.words }

        // Batas atas: di bawah baris statistik (postingan/pengikut)
        val statY = allWords
            .filter { it.norm == "postingan" || it.norm == "posts" || it.norm == "pengikut" || it.norm == "followers" }
            .filter { it.rect.centerY() < h * 0.30 }
            .maxOfOrNull { it.rect.centerY() } ?: (h * 0.15).toInt()
        val topLimit = statY + 40

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
            l.words.all { it.norm in BUTTON_WORDS || it.norm.length <= 2 || it.norm.startsWith("mengik") }
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

        val following = pick { it.norm.startsWith("mengik") || it.norm in FOLLOWING_WORDS }
        val followBtn = pick { it.norm in FOLLOW_WORDS }
        val followingOk = following != null &&
            (tapY < 0 || abs(following.rect.centerY() - tapY) < 150)

        val onTarget = headerMatchesTarget(lines, h)

        when (phase) {
            Phase.IN_INSTAGRAM -> {
                if (!onTarget) {
                    if (now - phaseSince > PROFILE_TIMEOUT) {
                        retryProfile(now, "Profil bukan target")
                    } else {
                        setStatus("Menunggu profil @${targetUser ?: "?"}…")
                    }
                } else if (following != null) {
                    verifiedFollow = true
                    returnToBrowser(now, "Sudah mengikuti")
                } else if (followBtn != null) {
                    tap(followBtn.rect)
                    tapY = followBtn.rect.centerY()
                    phase = Phase.IG_CLICKED
                    phaseSince = now
                    igRetries = 0
                    setStatus("Menekan tombol Ikuti…")
                } else if (now - phaseSince > PROFILE_TIMEOUT) {
                    retryProfile(now, "Tombol Ikuti tidak ketemu")
                } else {
                    setStatus("Mencari tombol Ikuti… (percobaan ${igAttempts + 1}/$MAX_PROFILE_TRIES)")
                }
            }
            Phase.IG_CLICKED -> {
                if (onTarget && followingOk) {
                    verifiedFollow = true
                    returnToBrowser(now, "Berhasil mengikuti")
                } else if (now - phaseSince > 3500) {
                    if (onTarget && followBtn != null && igRetries < 2) {
                        igRetries++
                        tap(followBtn.rect)
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

    /** Cocokkan nama akun di judul profil Instagram dengan akun target dari PR.SOCIAL. */
    private fun headerMatchesTarget(lines: List<OcrLine>, h: Int): Boolean {
        val t = targetUser?.filter { it.isLetterOrDigit() } ?: return true
        if (t.length < 3) return true
        val hdr = lines
            .filter { it.rect.centerY() in (h * 0.04).toInt()..(h * 0.12).toInt() }
            .joinToString("") { it.norm }
            .filter { it.isLetterOrDigit() }
        if (hdr.isEmpty()) return true
        if (hdr.contains(t)) return true
        if (hdr.length >= 5 && t.contains(hdr)) return true
        var n = 0
        while (n < hdr.length && n < t.length && hdr[n] == t[n]) n++
        return n >= minOf(6, t.length)
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
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg, kembali ke halaman tugas")
    }

    /** Gagal menemukan/menekan Ikuti: kembali ke web lalu buka profil lagi (bukan menekan Periksa). */
    private fun retryProfile(now: Long, msg: String) {
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

        // Misi yang minta screenshot tidak bisa dikerjakan bot -> batalkan + refresh web
        if (check == null && isScreenshotMission(lines, confirmMark)) {
            handleScreenshotMission(now)
            return
        }

        if (check == null && startBtn != null) {
            if (!canStartNew(now)) return
            tap(startBtn.rect)
            phase = Phase.LIST
            phaseSince = now
            setStatus("Menekan Mulai…")
            return
        }

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
                // 1. Setelah refresh halaman kembali ke "Semua": klik logo Instagram dulu
                if (needIgTab && selectInstagramTab(lines, now)) return
                // 2. Ada "Tugas sedang dikerjakan": kerjakan yang itu dulu (prioritas)
                if (resumeInProgress(lines, now)) return
                // 3. Baru ambil tugas baru
                listStep(lines, now)
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
                setStatus("Membuka profil Instagram…")
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

    private fun listStep(lines: List<OcrLine>, now: Long) {
        if (!canStartNew(now)) return
        val h = resources.displayMetrics.heightPixels
        val row = lines
            .filter {
                it.norm.contains("ikuti di instagram") &&
                    !it.norm.contains("dimulai") && !it.norm.contains("dikerjakan") &&
                    it.rect.centerY() > h * 0.18 && it.rect.centerY() < h * 0.85
            }
            .minByOrNull { it.rect.top }
        if (row == null) {
            idleTicks++
            setStatus("Tidak ada tugas Follow terlihat (OCR ${lines.size} baris)")
            if (idleTicks >= 4 && now - lastRefreshAt > 15000) {
                idleTicks = 0
                doRefresh(now, "Tugas habis")
            }
            return
        }
        idleTicks = 0
        tap(row.rect)
        phaseSince = now
        setStatus("Membuka tugas…")
    }

    private fun tapLink(lines: List<OcrLine>) {
        val link = lines.firstOrNull { it.norm.contains("instagram.com") }
            ?: lines.firstOrNull { it.norm.contains("buka profil dan ikuti") }
        if (link != null) tap(link.rect)
    }

    private fun canStartNew(now: Long): Boolean {
        if (now < nextAllowedAt) {
            setStatus("Jeda ${(nextAllowedAt - now) / 1000} detik…")
            return false
        }
        if (recentCount(now) >= MAX_PER_HOUR) {
            setStatus("Batas $MAX_PER_HOUR per jam tercapai. Menunggu…")
            return false
        }
        return true
    }

    private fun onTaskDone(now: Long) {
        doneCount++
        doneTimes.add(now)
        resetTask()
        nextAllowedAt = now + Random.nextLong(2000, 4000)
        setStatus("✅ Tugas selesai. Jeda sebelum tugas berikutnya.")
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
        taskRefreshes = 0
    }

    private fun recentCount(now: Long): Int {
        doneTimes.removeAll { now - it > 3_600_000L }
        return doneTimes.size
    }

    // ---------------------------------------------------------------- Refresh, tab, prioritas

    /** Refresh halaman web. Setelah refresh halaman balik ke tab "Semua", jadi tab Instagram diklik lagi. */
    private fun doRefresh(now: Long, msg: String) {
        val keep = taskRefreshes
        resetTask()
        taskRefreshes = keep
        needIgTab = true
        igTabTaps = 0
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

    /** Klik logo Instagram (tab ke-2, di sebelah kanan "Semua"). true = masih sibuk di langkah ini. */
    private fun selectInstagramTab(lines: List<OcrLine>, now: Long): Boolean {
        // Sudah di tab Instagram? judul daftar: "19 tugas · @akun"
        val onIg = lines.any {
            it.norm.contains("tugas") && it.norm.contains("@") && it.norm.firstOrNull()?.isDigit() == true
        }
        if (onIg) {
            needIgTab = false
            igTabTaps = 0
            return false
        }
        if (igTabTaps >= 3) {
            needIgTab = false
            return false
        }
        if (now - lastTabTapAt < 1800) {
            setStatus("Menunggu tab Instagram terbuka…")
            return true
        }
        val semua = lines.filter { it.norm == "semua" }.minByOrNull { it.rect.top }
        if (semua == null) {
            igTabTaps++
            setStatus("Mencari tab Instagram…")
            return true
        }
        val w = resources.displayMetrics.widthPixels
        val nums = lines.filter {
            Regex("^\\+?\\d+$").matches(it.norm) &&
                abs(it.rect.centerY() - semua.rect.centerY()) < 45 &&
                it.rect.centerX() > semua.rect.centerX()
        }.sortedBy { it.rect.centerX() }
        val target = nums.firstOrNull()?.rect ?: Rect().apply {
            val cx = (semua.rect.centerX() + w * 0.192f).toInt()
            val cy = semua.rect.centerY() - 40
            set(cx - 10, cy - 10, cx + 10, cy + 10)
        }
        tap(target)
        igTabTaps++
        lastTabTapAt = now
        setStatus("Klik logo Instagram dulu ($igTabTaps/3)…")
        return true
    }

    /** Banner "Tugas sedang dikerjakan" + tombol Lanjutkan: dahulukan tugas itu. true = sedang diurus. */
    private fun resumeInProgress(lines: List<OcrLine>, now: Long): Boolean {
        val banner = lines.firstOrNull { it.norm.contains("tugas sedang dikerjakan") } ?: return false
        val btn = lines
            .filter { it.norm.contains("lanjutkan") && abs(it.rect.centerY() - banner.rect.centerY()) < 160 }
            .minByOrNull { abs(it.rect.centerY() - banner.rect.centerY()) }
            ?: banner
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
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val IG_PACKAGE = "com.instagram.android"
        private const val MAX_PER_HOUR = 50
        private const val MAX_PROFILE_TRIES = 5
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
