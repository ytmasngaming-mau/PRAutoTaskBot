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
 * Bot otomatis tugas "Ikuti di Instagram" & "Ikuti di TikTok" di PR.SOCIAL.
 * Alur kerja:
 * 1. Prioritas 1: Tugas "sedang dikerjakan" (lanjutkan/kirim/tugas berikutnya).
 * 2. Di halaman Tugas saat mode "Semua": klik logo/kartu TikTok -> kerjakan misi Follow/Ikuti.
 * 3. Jika misi TikTok habis -> refresh web -> klik logo/kartu Instagram -> kerjakan misi Follow/Ikuti.
 * 4. Jika misi Instagram habis -> refresh web -> balik lagi ke logo/kartu TikTok (loop terus).
 * 5. Jeda tiap 10 misi selesai: acak 3-5 menit (180.000 - 300.000 ms).
 * 6. Tanpa limit internal 50/jam; saat muncul peringatan batas 50 di web (atau setelah jeda
 *    web selesai), terapkan jeda tambahan 5-10 menit (300.000 - 600.000 ms) lalu lanjutkan.
 */
class AutomationService : AccessibilityService() {

    private enum class Phase { LIST, IN_SOCIAL, SOCIAL_CLICKED, RETURNING, CHECKING }
    private enum class PlatformMode { TIKTOK, INSTAGRAM }

    private class OcrWord(val raw: String, val rect: Rect) {
        val norm: String = raw.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    }

    private class OcrLine(val text: String, val rect: Rect, val words: List<OcrWord>) {
        val norm: String = text.lowercase().replace(Regex("""\s+"""), " ").trim()
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
    private var activeMode = PlatformMode.TIKTOK
    private var phaseSince = 0L
    private var currentPkg: String? = null
    private var browserPkg: String? = null
    private var checkAttempts = 0
    private var socialRetries = 0
    private var socialAttempts = 0
    private var targetUser: String? = null
    private var tapY = -1
    private var verifiedFollow = false
    private var cancelAfterReturn = false
    private var idleTicks = 0
    private var lastRefreshAt = 0L
    private var nextAllowedAt = 0L
    private var doneCount = 0
    private var lastShotError = 0

    // Pelacakan jeda 50 / cooldown web agar setelah jeda web berakhir diberi jeda tambahan 5-10 mnt
    private var sawLimit50 = false
    private var postLimit50CooldownApplied = false
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
            maxWidth = 540
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
            sawLimit50 = false
            postLimit50CooldownApplied = false
            setStatus("Bot jalan (Loop: TikTok -> Refresh -> Instagram -> Refresh).")
        } else {
            setStatus("Bot dimatikan.")
        }
    }

    private fun refreshUi() {
        toggleView?.apply {
            text = if (enabled) "🤖 AUTO: ON" else "🤖 AUTO: OFF"
            setBackgroundColor(if (enabled) 0xEE2E7D32.toInt() else 0xEEC62828.toInt())
        }
        val modeLabel = if (activeMode == PlatformMode.TIKTOK) "TT" else "IG"
        statusView?.text = "$statusText\n✔ $doneCount selesai | Mode: $modeLabel"
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

    private fun isSocialScreen(pkg: String?, lines: List<OcrLine>): Boolean {
        if (pkg == IG_PACKAGE || pkg == TIKTOK_PACKAGE) return true
        val h = resources.displayMetrics.heightPixels
        val hasFollowAction = lines.any { line ->
            val n = line.norm
            line.rect.centerY() < h * 0.55 &&
                (n == "ikuti" || n == "follow" || n.startsWith("mengikuti") || n.startsWith("following"))
        }
        val hasSocialStats = lines.any { line ->
            val n = line.norm
            n.contains("pengikut") || n.contains("mengikuti") || n.contains("followers") ||
                n.contains("postingan") || (n.contains("suka") && line.rect.centerY() < h * 0.30)
        }
        val hasAtHandle = lines.any { it.raw.trim().startsWith("@") && it.rect.centerY() < h * 0.30 }
        return hasFollowAction && (hasSocialStats || hasAtHandle)
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

        if (isSocialScreen(pkg, lines)) {
            val detectedPkg = pkg ?: if (activeMode == PlatformMode.TIKTOK) TIKTOK_PACKAGE else IG_PACKAGE
            handleSocialApp(detectedPkg, lines, now)
            return
        }

        val looksPr = lines.any {
            val n = it.norm
            n.contains("ikuti di instagram") || n.contains("ikuti di tiktok") ||
                n.contains("periksa follow saya") || n.contains("buka profil dan ikuti") ||
                n.contains("tugas saya") || n.contains("sedang dikerjakan") ||
                n.contains("semua platform") || n.contains("bayaran tertinggi") ||
                n.contains("batas per jam tercapai") || n.contains("jeda untuk menjaga akun")
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

    // ---------------------------------------------------------------- TikTok & Instagram

    private fun handleSocialApp(pkg: String, lines: List<OcrLine>, now: Long) {
        if (lines.any {
                val n = it.norm
                n.contains("coba lagi nanti") || n.contains("try again later") ||
                    n.contains("tindakan diblokir") || n.contains("action blocked") ||
                    n.contains("terlalu sering") || n.contains("too many requests")
            }
        ) {
            enabled = false
            setStatus("⛔ Akun dibatasi platform. Bot dimatikan, istirahat dulu.")
            Toast.makeText(this, "Akun dibatasi platform. Bot dimatikan.", Toast.LENGTH_LONG).show()
            return
        }

        val h = resources.displayMetrics.heightPixels
        val allWords = lines.flatMap { it.words }

        val statY = allWords
            .filter {
                it.norm == "postingan" || it.norm == "posts" ||
                    it.norm == "pengikut" || it.norm == "followers" ||
                    it.norm == "mengikuti" || it.norm == "following" ||
                    it.norm == "suka" || it.norm == "likes"
            }
            .filter { it.rect.centerY() < h * 0.35 }
            .maxOfOrNull { it.rect.centerY() } ?: (h * 0.16).toInt()
        val topLimit = statY + 25

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
                    .filter { pred(it) && anchorYs.any { y -> abs(y - it.rect.centerY()) < 85 } }
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
        val followingOk = following != null &&
            (tapY < 0 || abs(following.rect.centerY() - tapY) < 170)

        val onTarget = headerMatchesTarget(lines, h)
        val platformLabel = if (pkg == TIKTOK_PACKAGE) "TikTok" else "Instagram"

        if (phase != Phase.IN_SOCIAL && phase != Phase.SOCIAL_CLICKED && phase != Phase.RETURNING) {
            phase = Phase.IN_SOCIAL
            phaseSince = now
        }

        when (phase) {
            Phase.IN_SOCIAL -> {
                if (!onTarget) {
                    if (now - phaseSince > PROFILE_TIMEOUT) {
                        retryProfile(now, "Profil $platformLabel bukan target")
                    } else {
                        setStatus("Menunggu profil @${targetUser ?: "?"} di $platformLabel…")
                    }
                } else if (following != null) {
                    verifiedFollow = true
                    returnToBrowser(now, "Sudah mengikuti di $platformLabel")
                } else if (followBtn != null) {
                    tap(followBtn.rect)
                    tapY = followBtn.rect.centerY()
                    phase = Phase.SOCIAL_CLICKED
                    phaseSince = now
                    socialRetries = 0
                    setStatus("Menekan tombol Ikuti di $platformLabel…")
                } else if (now - phaseSince > PROFILE_TIMEOUT) {
                    retryProfile(now, "Tombol Ikuti di $platformLabel tidak ketemu")
                } else {
                    setStatus("Mencari tombol Ikuti ($platformLabel)… (${socialAttempts + 1}/$MAX_PROFILE_TRIES)")
                }
            }
            Phase.SOCIAL_CLICKED -> {
                if (onTarget && followingOk) {
                    verifiedFollow = true
                    returnToBrowser(now, "Berhasil mengikuti di $platformLabel")
                } else if (now - phaseSince > 3500) {
                    if (onTarget && followBtn != null && socialRetries < 2) {
                        socialRetries++
                        tap(followBtn.rect)
                        phaseSince = now
                    } else {
                        retryProfile(now, "Ikuti belum terdeteksi di $platformLabel")
                    }
                }
            }
            Phase.RETURNING -> {
                if (now - phaseSince > 2200) {
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
            .filter { it.rect.centerY() in (h * 0.03).toInt()..(h * 0.22).toInt() }
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
        socialAttempts++
        verifiedFollow = false
        tapY = -1
        cancelAfterReturn = socialAttempts >= MAX_PROFILE_TRIES
        if (browserPkg != null) launchBrowser() else performGlobalAction(GLOBAL_ACTION_BACK)
        phase = Phase.RETURNING
        phaseSince = now
        setStatus("$msg. Ulangi buka profil ($socialAttempts/$MAX_PROFILE_TRIES)")
    }

    // ---------------------------------------------------------------- Halaman tugas PR.SOCIAL

    private fun hasLimit50Warning(lines: List<OcrLine>): Boolean {
        return lines.any {
            val n = it.norm
            n.contains("batas per jam tercapai") ||
                n.contains("jeda untuk menjaga akun") ||
                (n.contains("50") && (n.contains("batas") || n.contains("mnt") || n.contains("menit") || n.contains("jeda")))
        }
    }

    private fun handlePr(lines: List<OcrLine>, now: Long) {
        val limit50Now = hasLimit50Warning(lines)
        if (limit50Now) {
            sawLimit50 = true
            postLimit50CooldownApplied = false
            setStatus("⏳ Peringatan batas 50 terdeteksi di web. Menunggu jeda web selesai…")
        } else if (sawLimit50 && !postLimit50CooldownApplied) {
            sawLimit50 = false
            postLimit50CooldownApplied = true
            val extraMs = Random.nextLong(5 * 60_000L, 10 * 60_000L + 1)
            nextAllowedAt = maxOf(nextAllowedAt, now + extraMs)
            val mins = (extraMs + 30_000L) / 60_000L
            setStatus("⏳ Jeda web 50 selesai. Jeda sistem tambahan ~$mins menit…")
            return
        }

        if (now < nextAllowedAt) {
            val remSec = (nextAllowedAt - now) / 1000
            val remMin = remSec / 60
            val remModSec = remSec % 60
            setStatus("⏸ Jeda aktif: ${remMin}m ${remModSec}d lagi…")
            return
        }

        val hasInProgressModal = lines.any { it.norm.contains("sedang dikerjakan") }
        val check = lines.firstOrNull {
            it.norm.contains("periksa follow saya") ||
                (it.norm.contains("periksa") && (it.norm.contains("follow") || it.norm.contains("saya") || it.norm.contains("tugas")))
        }
        val submitBtn = lines.firstOrNull {
            it.norm.contains("dikirim untuk ditinjau") || it.norm.contains("kirim untuk ditinjau")
        }
        val reviewed = lines.any { it.norm.contains("ditinjau") || it.norm.contains("disetujui") }
        val startBtn = lines.firstOrNull {
            it.norm.startsWith("mulai") && !it.norm.contains("dalam") && it.norm.any { c -> c.isDigit() }
        }
        val confirmMark = lines.any { it.norm.contains("konfirmasi follow anda") || it.norm.contains("konfirmasi") }
        val modalOpen = hasInProgressModal || check != null || submitBtn != null || startBtn != null || confirmMark || reviewed

        if (phase == Phase.CHECKING && (!modalOpen || reviewed)) {
            onTaskDone(now)
            return
        }

        if (submitBtn != null) {
            tap(submitBtn.rect)
            phase = Phase.CHECKING
            phaseSince = now
            setStatus("Mengirim tugas untuk ditinjau…")
            return
        }

        if (check == null && startBtn != null) {
            tap(startBtn.rect)
            phase = Phase.LIST
            phaseSince = now
            setStatus("Menekan Mulai…")
            return
        }

        val nextBtn = lines.firstOrNull { it.norm.contains("tugas berikutnya") }
        if (check == null && nextBtn != null) {
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
            setStatus("Menunggu jendela tugas sedang dikerjakan…")
            return
        }

        when (phase) {
            Phase.LIST -> {
                targetUser = parseTarget(lines)
                tapLink(lines)
                phase = Phase.IN_SOCIAL
                phaseSince = now
                checkAttempts = 0
                socialRetries = 0
                socialAttempts = 0
                verifiedFollow = false
                cancelAfterReturn = false
                tapY = -1
                setStatus("Membuka profil target…")
            }
            Phase.IN_SOCIAL -> {
                if (now - phaseSince > 8000) {
                    socialAttempts++
                    if (socialAttempts >= MAX_PROFILE_TRIES) {
                        cancelTask(lines, now)
                    } else {
                        tapLink(lines)
                        phaseSince = now
                        setStatus("Link belum terbuka, coba lagi ($socialAttempts/$MAX_PROFILE_TRIES)")
                    }
                }
            }
            Phase.SOCIAL_CLICKED, Phase.RETURNING -> {
                if (cancelAfterReturn) {
                    cancelTask(lines, now)
                } else if (!verifiedFollow) {
                    tapLink(lines)
                    phase = Phase.IN_SOCIAL
                    phaseSince = now
                    setStatus("Membuka ulang profil target…")
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
        val h = resources.displayMetrics.heightPixels

        val tiktokRow = lines
            .filter {
                it.norm.contains("ikuti di tiktok") &&
                    it.rect.centerY() > h * 0.25 && it.rect.centerY() < h * 0.88
            }
            .minByOrNull { it.rect.top }

        val igRow = lines
            .filter {
                it.norm.contains("ikuti di instagram") &&
                    it.rect.centerY() > h * 0.25 && it.rect.centerY() < h * 0.88
            }
            .minByOrNull { it.rect.top }

        val ttLogo = findPlatformLogoCard(lines, h, isTikTok = true)
        val igLogo = findPlatformLogoCard(lines, h, isTikTok = false)
        val isAllPlatformView = lines.any { it.norm.contains("semua platform") }

        if (activeMode == PlatformMode.TIKTOK) {
            if (tiktokRow != null) {
                idleTicks = 0
                tap(tiktokRow.rect)
                phaseSince = now
                setStatus("Membuka tugas Ikuti di TikTok…")
                return
            }
            if (ttLogo != null && (isAllPlatformView || idleTicks < 2)) {
                idleTicks++
                tap(ttLogo.rect)
                setStatus("Menekan logo TikTok…")
                return
            }
            idleTicks++
            setStatus("Misi TikTok habis / belum ada ($idleTicks)")
            if (idleTicks >= 3 && now - lastRefreshAt > 10_000) {
                idleTicks = 0
                lastRefreshAt = now
                activeMode = PlatformMode.INSTAGRAM
                setStatus("Misi TikTok habis, refresh lalu ke Instagram…")
                refreshBrowserPage()
            }
        } else {
            if (igRow != null) {
                idleTicks = 0
                tap(igRow.rect)
                phaseSince = now
                setStatus("Membuka tugas Ikuti di Instagram…")
                return
            }
            if (igLogo != null && (isAllPlatformView || idleTicks < 2)) {
                idleTicks++
                tap(igLogo.rect)
                setStatus("Menekan logo Instagram…")
                return
            }
            idleTicks++
            setStatus("Misi Instagram habis / belum ada ($idleTicks)")
            if (idleTicks >= 3 && now - lastRefreshAt > 10_000) {
                idleTicks = 0
                lastRefreshAt = now
                activeMode = PlatformMode.TIKTOK
                setStatus("Misi Instagram habis, refresh lalu balik ke TikTok…")
                refreshBrowserPage()
            }
        }
    }

    private fun findPlatformLogoCard(lines: List<OcrLine>, h: Int, isTikTok: Boolean): OcrLine? {
        val topBand = (h * 0.12).toInt()..(h * 0.26).toInt()
        val candidates = lines.filter { line ->
            if (line.rect.centerY() !in topBand) return@filter false
            val n = line.norm
            if (isTikTok) {
                n == "tiktok" || (n.contains("tiktok") && !n.contains("ikuti di tiktok"))
            } else {
                n == "instagram" || (n.contains("instagram") && !n.contains("ikuti di instagram"))
            }
        }
        if (candidates.isNotEmpty()) return candidates.first()

        val numCards = lines.filter { line ->
            line.rect.centerY() in topBand && line.norm.matches(Regex("""\d+"""))
        }.sortedBy { it.rect.left }

        if (numCards.size >= 2) {
            return if (isTikTok) numCards[1] else numCards[0]
        }
        return null
    }

    private fun tapLink(lines: List<OcrLine>) {
        val link = lines.firstOrNull {
            it.norm.contains("instagram.com") || it.norm.contains("tiktok.com")
        } ?: lines.firstOrNull {
            it.norm.contains("buka profil dan ikuti") || it.norm.contains("buka profil")
        }
        if (link != null) tap(link.rect)
    }

    private fun onTaskDone(now: Long) {
        doneCount++
        resetTask()

        if (doneCount % 10 == 0) {
            val breakMs = Random.nextLong(3 * 60_000L, 5 * 60_000L + 1)
            nextAllowedAt = now + breakMs
            val mins = (breakMs + 30_000L) / 60_000L
            setStatus("✅ $doneCount misi selesai! Jeda rutin ~$mins menit (3-5 mnt)…")
        } else {
            nextAllowedAt = now + Random.nextLong(2000, 4000)
            setStatus("✅ Tugas selesai ($doneCount). Lanjut misi berikutnya…")
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
        socialRetries = 0
        socialAttempts = 0
        verifiedFollow = false
        cancelAfterReturn = false
        tapY = -1
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

    private fun refreshBrowserPage() {
        val pkg = browserPkg ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://pr.social/tasks"))
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
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val MAX_PROFILE_TRIES = 5
        private const val PROFILE_TIMEOUT = 25_000L
        private val FOLLOW_WORDS = setOf("ikuti", "follow")
        private val FOLLOWING_WORDS = setOf("mengikuti", "following", "diminta", "requested")
        private val ANCHOR_WORDS = setOf(
            "kirim", "pesan", "message", "berlangganan", "subscribe", "kontak", "hubungi",
            "contact", "email", "telepon", "call", "petunjuk", "directions", "mengikuti"
        )
        private val BUTTON_WORDS = setOf(
            "ikuti", "follow", "balik", "back", "kirim", "pesan", "message",
            "berlangganan", "subscribe", "mengikuti", "following", "diminta", "requested",
            "kontak", "hubungi", "contact", "email", "telepon", "call", "petunjuk", "directions"
        )
    }
}
