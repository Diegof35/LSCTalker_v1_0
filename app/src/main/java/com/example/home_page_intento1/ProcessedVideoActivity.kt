package com.example.home_page_intento1

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import kotlin.math.sqrt

class ProcessedVideoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "processedVideoUri"
        const val MODEL_PROPOSED  = 0   // index of "Método propuesto" in models list
    }

    // ══ State machine ═════════════════════════════════════════════
    // SELECTING  → overlay ON,  model list interactive, no progress, text empty
    // PROCESSING → overlay ON,  model list locked, progress animating, text empty
    // COMPLETE   → overlay OFF, model list interactive (reprocess), text visible
    private enum class ProcState { SELECTING, PROCESSING, COMPLETE }
    private var procState = ProcState.SELECTING

    // ══ UI views ══════════════════════════════════════════════════
    private lateinit var btnProcBack:            ImageButton
    private lateinit var procPlayerView:         PlayerView
    private lateinit var procBtnPlayPause:       ImageButton
    private lateinit var procBtnSpeaker:         ImageButton
    private lateinit var procBtnRead:            ImageButton
    private lateinit var procProgressBar:        ProgressBar
    private lateinit var txtProcPercent:         TextView
    private lateinit var procBtnCancel:          ImageButton
    private lateinit var procBtnCopy:            ImageButton
    private lateinit var txtProcDescription:     TextView
    private lateinit var overlayDim:             View
    private lateinit var procModelListContainer: FrameLayout
    private lateinit var procModelListInner:     LinearLayout

    // ══ ExoPlayer ═════════════════════════════════════════════════
    private var exoPlayer: ExoPlayer? = null
    private val playPauseHandler = Handler(Looper.getMainLooper())
    private val HIDE_DELAY_MS = 2000L

    // ══ Progress animation ════════════════════════════════════════
    private var progressAnimator: ValueAnimator? = null
    private var selectedModelIdx  = -1

    // ══ Proposed-method coordination ══════════════════════════════
    @Volatile private var isProposedRun  = false
    private var proposedResult: String?  = null

    // ══ Landmark overlay ══════════════════════════════════════════
    /** Frame timestamp + raw MediaPipe landmarks (x,y,z in [0,1]) */
    private data class LandmarkFrameData(val timestampMs: Long, val landmarks: List<FloatArray>)

    private lateinit var imgLandmarkOverlay:  ImageView
    private var landmarkFrames: List<LandmarkFrameData>   = emptyList()
    private val overlayCache  = mutableMapOf<Long, Bitmap>()  // pre-rendered transparent skeletons
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null

    // ══ Prediction models ═════════════════════════════════════════
    private val models = listOf("Método propuesto", "Modelo MLP", "Modelo V_1.0", "Modelo V_2.0")

    // ══ Placeholder text (non-proposed models) ═══════════════════
    private val PREDICTION_TEXT =
        "La lengua de señas es un sistema visual y gestual que permite la comunicación de " +
                "personas sordas. Tiene gramática propia y varía según cada país, siendo una lengua " +
                "completa que promueve inclusión y accesibilidad."

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_processed_video)
        initViews()
        buildModelList()
        setupButtons()
        setupNavigation()
        setState(ProcState.SELECTING)
        val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (!uriStr.isNullOrBlank()) setupPlayer(Uri.parse(uriStr))
        else Toast.makeText(this, "No se encontró el video grabado", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgress()
        stopLandmarkOverlay()
        overlayCache.values.forEach { it.recycle() }
        overlayCache.clear()
        releasePlayer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        stopProgress()
        releasePlayer()
        finish()
    }

    // ═════════════════════════════════════════════════════════════
    // INIT
    // ═════════════════════════════════════════════════════════════
    private fun initViews() {
        btnProcBack            = findViewById(R.id.btnProcBack)
        procPlayerView         = findViewById(R.id.procPlayerView)
        procBtnPlayPause       = findViewById(R.id.procBtnPlayPause)
        procBtnSpeaker         = findViewById(R.id.procBtnSpeaker)
        procBtnRead            = findViewById(R.id.procBtnRead)
        procProgressBar        = findViewById(R.id.procProgressBar)
        txtProcPercent         = findViewById(R.id.txtProcPercent)
        procBtnCancel          = findViewById(R.id.procBtnCancel)
        procBtnCopy            = findViewById(R.id.procBtnCopy)
        txtProcDescription     = findViewById(R.id.txtProcDescription)
        overlayDim             = findViewById(R.id.overlayDim)
        procModelListContainer = findViewById(R.id.procModelListContainer)
        procModelListInner     = findViewById(R.id.procModelListInner)
        imgLandmarkOverlay     = findViewById(R.id.imgLandmarkOverlay)
    }

    // ═════════════════════════════════════════════════════════════
    // MODEL LIST – built programmatically
    // ═════════════════════════════════════════════════════════════
    private fun buildModelList() {
        procModelListInner.removeAllViews()
        models.forEachIndexed { i, name -> procModelListInner.addView(buildModelRow(i, name)) }
    }

    private fun buildModelRow(idx: Int, name: String): View {
        val dp = resources.displayMetrics.density

        val wrapper = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.WHITE)
        }

        val row = LinearLayout(this).apply {
            orientation   = LinearLayout.HORIZONTAL
            gravity       = Gravity.CENTER_VERTICAL
            minimumHeight = (44 * dp).toInt()
            setPadding((14*dp).toInt(), (4*dp).toInt(), (12*dp).toInt(), (4*dp).toInt())
            layoutParams  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            tag = "row_$idx"
        }

        val tv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name; textSize = 13f
            setTextColor(Color.parseColor("#222222"))
        }
        val ck = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "✓"; textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            visibility = View.INVISIBLE
            tag = "ck_$idx"
        }
        row.addView(tv); row.addView(ck)
        row.setOnClickListener { onModelTapped(idx) }
        wrapper.addView(row)

        if (idx < models.size - 1) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                ).apply { marginStart = (14*dp).toInt() }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            wrapper.addView(divider)
        }
        return wrapper
    }

    private fun setCheck(idx: Int) {
        models.indices.forEach { i ->
            procModelListInner.findViewWithTag<TextView>("ck_$i")
                ?.visibility = if (i == idx) View.VISIBLE else View.INVISIBLE
        }
        selectedModelIdx = idx
    }

    private fun setRowsClickable(enabled: Boolean) {
        models.indices.forEach { i ->
            procModelListInner.findViewWithTag<LinearLayout>("row_$i")
                ?.isClickable = enabled
        }
    }

    // ═════════════════════════════════════════════════════════════
    // MODEL TAP HANDLER
    // ═════════════════════════════════════════════════════════════
    private fun onModelTapped(idx: Int) {
        when (procState) {
            ProcState.SELECTING -> {
                setCheck(idx)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (idx == MODEL_PROPOSED) runProposedMethod()
                    else                       setState(ProcState.PROCESSING)
                }, 350)
            }
            ProcState.COMPLETE -> {
                AlertDialog.Builder(this)
                    .setTitle("¿Usar otro modelo?")
                    .setMessage("Se borrará la predicción actual y se procesará con \"${models[idx]}\".")
                    .setPositiveButton("Confirmar") { _, _ ->
                        setCheck(idx)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (idx == MODEL_PROPOSED) runProposedMethod()
                            else                       setState(ProcState.PROCESSING)
                        }, 350)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            ProcState.PROCESSING -> { /* locked – ignore taps */ }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PROPOSED METHOD ENTRY POINT
    // Starts animator + background processing in parallel.
    // COMPLETE fires only when BOTH animator and processing are done.
    // ═════════════════════════════════════════════════════════════
    private fun runProposedMethod() {
        isProposedRun  = true
        proposedResult = null
        landmarkFrames = emptyList()
        stopLandmarkOverlay()
        overlayCache.values.forEach { it.recycle() }
        overlayCache.clear()

        // Enter PROCESSING without ValueAnimator — bar stays at 0% while work runs.
        procState = ProcState.PROCESSING
        overlayDim.visibility    = View.VISIBLE
        overlayDim.alpha         = 0.6f
        setRowsClickable(false)
        procBtnCancel.isEnabled  = true;  procBtnCancel.alpha = 1f
        procBtnCopy.isEnabled    = false; procBtnCopy.alpha   = 0.4f
        txtProcDescription.text  = ""
        procProgressBar.progress = 0
        txtProcPercent.text      = "..."   // signals processing in progress

        val uriStr   = intent.getStringExtra(EXTRA_VIDEO_URI)
        val videoUri = if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null

        Thread {
            val (result, frames, dims) = PropuestoProcessor().process(videoUri) { pct ->
                runOnUiThread {
                    if (procState == ProcState.PROCESSING) {
                        procProgressBar.progress = pct
                        txtProcPercent.text      = "$pct%"
                    }
                }
            }

            // Pre-render transparent skeleton overlays for every detected frame.
            // Done on background thread so UI never blocks.
            val cache = mutableMapOf<Long, Bitmap>()
            if (dims != null && frames.isNotEmpty()) {
                for (lfd in frames) {
                    cache[lfd.timestampMs] =
                        drawLandmarkOverlay(dims.first, dims.second, lfd.landmarks)
                }
            }

            runOnUiThread {
                if (!isProposedRun) {
                    cache.values.forEach { it.recycle() }
                    return@runOnUiThread
                }
                proposedResult = result
                landmarkFrames = frames
                overlayCache.putAll(cache)
                setState(ProcState.COMPLETE)
            }
        }.start()
    }

    // ═════════════════════════════════════════════════════════════
    // STATE MACHINE
    // ═════════════════════════════════════════════════════════════
    private fun setState(state: ProcState) {
        procState = state
        when (state) {
            ProcState.SELECTING  -> enterSelecting()
            ProcState.PROCESSING -> enterProcessing()
            ProcState.COMPLETE   -> enterComplete()
        }
    }

    /** SELECTING: overlay on, progress reset, text empty, rows clickable */
    private fun enterSelecting() {
        overlayDim.visibility    = View.VISIBLE
        overlayDim.alpha         = 0.6f
        setRowsClickable(true)
        procProgressBar.progress = 0
        txtProcPercent.text      = "0%"
        procBtnCancel.isEnabled  = false; procBtnCancel.alpha = 0.4f
        procBtnCopy.isEnabled    = false; procBtnCopy.alpha   = 0.4f
        txtProcDescription.text  = ""
        stopLandmarkOverlay()
        imgLandmarkOverlay.setImageBitmap(null)
        imgLandmarkOverlay.visibility = View.GONE
        procPlayerView.visibility     = View.VISIBLE
    }

    /** PROCESSING: overlay on, rows locked, progress animating, cancel active */
    private fun enterProcessing() {
        overlayDim.visibility    = View.VISIBLE
        overlayDim.alpha         = 0.6f
        setRowsClickable(false)
        procBtnCancel.isEnabled  = true;  procBtnCancel.alpha = 1f
        procBtnCopy.isEnabled    = false; procBtnCopy.alpha   = 0.4f
        txtProcDescription.text  = ""
        startProgress()
    }

    /** COMPLETE: fade overlay, text visible, copy enabled, rows clickable for reprocess */
    private fun enterComplete() {
        stopProgress()
        procProgressBar.progress = 100
        txtProcPercent.text      = "100%"
        procBtnCancel.isEnabled  = false; procBtnCancel.alpha = 0.4f
        procBtnCopy.isEnabled    = true;  procBtnCopy.alpha   = 1f
        setRowsClickable(true)
        setPredictionText()
        // For proposed method: play original video + sync landmark overlay on top.
        // For other models: plain video, no overlay.
        procPlayerView.visibility     = View.VISIBLE
        if (selectedModelIdx == MODEL_PROPOSED && landmarkFrames.isNotEmpty()) {
            imgLandmarkOverlay.visibility = View.VISIBLE
            startLandmarkOverlay()
        } else {
            imgLandmarkOverlay.visibility = View.GONE
        }
        ValueAnimator.ofFloat(0.6f, 0f).apply {
            duration     = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { overlayDim.alpha = it.animatedValue as Float }
            start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            overlayDim.visibility = View.GONE
        }, 520)
    }

    // ═════════════════════════════════════════════════════════════
    // PROGRESS ANIMATION – 3 000 ms ValueAnimator
    //
    // Two-flag gate for "Método propuesto":
    //   • Animator ends → sets animatorDone.
    //       - If processDone already true  → COMPLETE
    //       - If processDone still false   → hold at 99%, wait for background thread
    //   • Background thread ends → sets processDone.
    //       - If animatorDone already true → COMPLETE
    //       - If animatorDone still false  → animator will finish the transition
    //
    // All other models: direct COMPLETE on animator end (existing behavior, unchanged).
    // ═════════════════════════════════════════════════════════════
    private fun startProgress() {
        stopProgress()
        progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration     = 3000L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                procProgressBar.progress = v
                txtProcPercent.text      = "$v%"
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Only fires for non-proposed models (proposed uses real callbacks)
                    if (procState == ProcState.PROCESSING) setState(ProcState.COMPLETE)
                }
            })
            start()
        }
    }

    private fun stopProgress() {
        progressAnimator?.cancel()
        progressAnimator = null
    }

    // ═════════════════════════════════════════════════════════════
    // PREDICTION TEXT
    // "Método propuesto" → real predicted sign label from PropuestoProcessor.
    // Other models       → static placeholder text (PREDICTION_TEXT).
    // Text shown ONLY in COMPLETE state (enterComplete calls this).
    // ═════════════════════════════════════════════════════════════
    private fun setPredictionText() {
        val displayText = if (selectedModelIdx == MODEL_PROPOSED && proposedResult != null) {
            "Seña detectada: ${proposedResult!!}"
        } else {
            PREDICTION_TEXT
        }
        val span = SpannableString(displayText)
        val end  = (displayText.indexOf('.') + 1).coerceAtMost(displayText.length)
        span.setSpan(ForegroundColorSpan(Color.parseColor("#1565C0")),
            0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(UnderlineSpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (end < displayText.length) {
            span.setSpan(ForegroundColorSpan(Color.BLACK),
                end, displayText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(UnderlineSpan(),
                end, displayText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        txtProcDescription.text = span
    }

    // ═════════════════════════════════════════════════════════════
    // BUTTONS
    // ═════════════════════════════════════════════════════════════
    private fun setupButtons() {
        btnProcBack.setOnClickListener { stopProgress(); releasePlayer(); finish() }

        procBtnPlayPause.setOnClickListener {
            exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            schedulePlayPauseHide()
        }
        procPlayerView.setOnClickListener { showPlayPauseButton() }

        // Cancel: stop progress and invalidate any in-flight proposed processing
        procBtnCancel.setOnClickListener {
            if (procState == ProcState.PROCESSING) {
                stopProgress()
                isProposedRun  = false
                proposedResult = null
                setCheck(-1)
                procProgressBar.progress = 0
                txtProcPercent.text      = "0%"
                setState(ProcState.SELECTING)
            }
        }

        procBtnSpeaker.setOnClickListener {
            Toast.makeText(this, "Audio – próximamente",  Toast.LENGTH_SHORT).show()
        }
        procBtnRead.setOnClickListener {
            Toast.makeText(this, "Leer – próximamente",   Toast.LENGTH_SHORT).show()
        }
        procBtnCopy.setOnClickListener {
            if (procState == ProcState.COMPLETE)
                Toast.makeText(this, "Copiar – próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LANDMARK OVERLAY – real-time sync with ExoPlayer position
    // ═════════════════════════════════════════════════════════════
    /** Polls every 33ms; shows pre-rendered skeleton when player is near a detected frame. */
    private fun startLandmarkOverlay() {
        stopLandmarkOverlay()
        overlayRunnable = object : Runnable {
            override fun run() {
                val pos     = exoPlayer?.currentPosition ?: 0L
                val nearest   = landmarkFrames.minByOrNull { kotlin.math.abs(it.timestampMs - pos) }
                // 100ms threshold = full frame interval → consecutive detected frames tile seamlessly
                if (nearest != null && kotlin.math.abs(nearest.timestampMs - pos) <= 100L) {
                    imgLandmarkOverlay.setImageBitmap(overlayCache[nearest.timestampMs])
                } else {
                    imgLandmarkOverlay.setImageBitmap(null)   // no hand in this range → clear
                }
                overlayHandler.postDelayed(this, 33L)
            }
        }
        overlayHandler.post(overlayRunnable!!)
    }

    private fun stopLandmarkOverlay() {
        overlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        overlayRunnable = null
    }

    /**
     * Creates a transparent ARGB_8888 bitmap with the hand skeleton drawn on it.
     * rawLandmarks[i] = [x, y, z] normalized to [0,1] by MediaPipe.
     */
    private fun drawLandmarkOverlay(w: Int, h: Int, landmarks: List<FloatArray>): Bitmap {
        val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)  // transparent
        val canvas = Canvas(bmp)
        // Proportional to shorter dimension so size is consistent across resolutions
        val strokeW = (minOf(w, h) * 0.003f).coerceAtLeast(1f)
        val radius  = (minOf(w, h) * 0.006f).coerceAtLeast(3f)

        val linePaint = Paint().apply {
            color       = Color.WHITE
            strokeWidth = strokeW
            style       = Paint.Style.STROKE
            isAntiAlias = true
        }
        val dotPaint = Paint().apply {
            color       = Color.parseColor("#00E676")
            style       = Paint.Style.FILL
            isAntiAlias = true
        }
        val connections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
        for ((a, b) in connections) {
            if (a < landmarks.size && b < landmarks.size) {
                canvas.drawLine(
                    landmarks[a][0] * w, landmarks[a][1] * h,
                    landmarks[b][0] * w, landmarks[b][1] * h,
                    linePaint
                )
            }
        }
        for (lm in landmarks) {
            canvas.drawCircle(lm[0] * w, lm[1] * h, radius, dotPaint)
        }
        return bmp
    }

    // ═════════════════════════════════════════════════════════════
    // EXOPLAYER
    // ═════════════════════════════════════════════════════════════
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupPlayer(videoUri: Uri) {
        releasePlayer()
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            procPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> showPlayPauseButton()
                        Player.STATE_ENDED -> { player.seekTo(0); player.pause(); updatePPIcon(false) }
                        else -> {}
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { updatePPIcon(isPlaying) }
            })
            player.setMediaItem(MediaItem.fromUri(videoUri))
            player.prepare(); player.play()
        }
    }

    private fun releasePlayer() {
        playPauseHandler.removeCallbacksAndMessages(null)
        exoPlayer?.stop(); exoPlayer?.release(); exoPlayer = null
    }

    private fun updatePPIcon(isPlaying: Boolean) {
        procBtnPlayPause.setImageResource(
            if (isPlaying) R.drawable.icon_ic_pause else R.drawable.icon_ic_play
        )
    }

    private fun showPlayPauseButton() {
        updatePPIcon(exoPlayer?.isPlaying ?: false)
        procBtnPlayPause.visibility = View.VISIBLE
        schedulePlayPauseHide()
    }

    private fun schedulePlayPauseHide() {
        playPauseHandler.removeCallbacksAndMessages(null)
        playPauseHandler.postDelayed({ procBtnPlayPause.visibility = View.GONE }, HIDE_DELAY_MS)
    }

    // ═════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═════════════════════════════════════════════════════════════
    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btnProcNavHome).setOnClickListener {
            stopProgress(); releasePlayer()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<ImageButton>(R.id.btnProcNavVideos).setOnClickListener {
            stopProgress(); releasePlayer()
            startActivity(Intent(this, SearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<ImageButton>(R.id.btnProcNavLearn).setOnClickListener {
            stopProgress(); releasePlayer()
            startActivity(Intent(this, LearnActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<ImageButton>(R.id.btnProcNavProfile).setOnClickListener {
            Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ══ PROPUESTO PROCESSOR ══════════════════════════════════════════════════
    //
    // Android port of CodigoColab.txt — Section 2.
    //
    // Pipeline (mirrors Python logic step by step):
    //   1. loadDatabase()      — reads assets/signs_database/*.csv
    //   2. extractFrames()     — MediaMetadataRetriever (standard API, no extra dep)
    //   3. extractLandmarks()  — MediaPipe stub (TODO: add Gradle dep, see below)
    //   4. normalize()         — min-max per axis; exact port of normalize_hand_coordinates()
    //   5. checkMatch()        — Euclidean distance per landmark; port of check_match()
    //   6. findBestMatch()     — iterates database; port of detect_sign()
    //   7. majority vote       — port of winner_sign logic
    //
    // ── SETUP REQUIRED BEFORE RUNNING ──────────────────────────────────────
    //
    //   A) Signs database CSV files:
    //      Copy your existing CSV files from Google Drive into:
    //        app/src/main/assets/signs_database/
    //      (create the folder if it does not exist)
    //      Each CSV must match Colab format:
    //        Line 1 comment: # Label: X
    //        Columns:        sample, hand_type, landmark_id, x_norm, y_norm, z_norm
    //
    //   B) MediaPipe for Android (Phase 2 of roadmap):
    //      Add to build.gradle.kts dependencies block:
    //        implementation("com.google.mediapipe:solution-core:latest.release")
    //        implementation("com.google.mediapipe:hands:latest.release")
    //      Then replace the extractLandmarks() stub below with real MediaPipe code.
    //      Until done: process() returns first loaded sign + "(demo)" suffix.
    //
    // ─────────────────────────────────────────────────────────────────────────
    private inner class PropuestoProcessor {

        // ── Constants matching Colab configuration ────────────────────────
        private val DISTANCE_THRESHOLD     = 0.20f   // matches DISTANCE_THRESHOLD in Colab
        private val MIN_MATCHING_LANDMARKS = 18       // relaxed from 21 to tolerate minor noise
        private val FRAME_INTERVAL_MS      = 100L     // ~10 fps extraction

        // Database: label → list of normalized samples (each sample = Array<FloatArray>(21))
        private val signDatabase = mutableMapOf<String, MutableList<Array<FloatArray>>>()

        // ── Main entry point (called on background thread) ────────────────
        // Returns Triple(label, detectedFrames, videoDimensions).
        // detectedFrames: list of LandmarkFrameData for pre-rendering skeleton overlays.
        // videoDimensions: Pair(width, height) of extracted frames (for overlay rendering).
        // onProgress(0..100): called at ~10 milestones to update bar without blocking.
        fun process(videoUri: Uri?, onProgress: (Int) -> Unit = {}): Triple<String, List<LandmarkFrameData>, Pair<Int,Int>?> {
            val TAG = "PropuestoProc"

            val dbLoaded = loadDatabase()
            Log.d(TAG, "DB cargada: $dbLoaded | señas: ${signDatabase.size} | labels: ${signDatabase.keys}")
            if (!dbLoaded || signDatabase.isEmpty()) {
                Log.e(TAG, "ERROR: base de datos vacía o no encontrada")
                return Triple("Base de datos no disponible. Agregue CSVs en assets/signs_database/", emptyList(), null)
            }

            if (videoUri == null) {
                Log.w(TAG, "WARN: videoUri es null")
                return Triple("${signDatabase.keys.first()} (demo – sin video)", emptyList(), null)
            }
            Log.d(TAG, "videoUri: $videoUri")

            val frames = extractFrames(videoUri)
            Log.d(TAG, "Frames extraídos: ${frames.size}")
            if (frames.isEmpty()) {
                Log.e(TAG, "ERROR: no se extrajeron frames del video")
                return Triple("No se pudieron extraer frames del video", emptyList(), null)
            }

            val totalFrames = frames.size.coerceAtLeast(1)
            // Fire progress update every ~10% of total frames (max 10 UI posts)
            val milestone   = (totalFrames / 10).coerceAtLeast(1)
            val dims        = Pair(frames[0].width, frames[0].height)
            val detections  = mutableListOf<String>()
            val detectedLfs = mutableListOf<LandmarkFrameData>()

            val handsOptions = HandsOptions.builder()
                .setStaticImageMode(true)
                .setMaxNumHands(1)
                .setModelComplexity(0)
                .setMinDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            Log.d(TAG, "HandsOptions creado — inicializando Hands...")
            val handsDetector = Hands(applicationContext, handsOptions)
            var frameLandmarks: List<FloatArray>? = null

            handsDetector.setErrorListener { msg, e ->
                Log.e(TAG, "MediaPipe error: $msg", e)
                frameLandmarks = null
            }

            try {
                var frameIdx = 0
                var framesWithHand = 0
                for (frame in frames) {
                    if (!isProposedRun) { Log.d(TAG, "Cancelado en frame $frameIdx"); break }
                    frameLandmarks = null
                    val latch = java.util.concurrent.CountDownLatch(1)

                    handsDetector.setResultListener { result ->
                        val lms = result.multiHandLandmarks()?.firstOrNull()
                        frameLandmarks = lms?.landmarkList?.map { lm ->
                            floatArrayOf(lm.x, lm.y, lm.z)
                        }
                        Log.v(TAG, "Listener — manos: ${result.multiHandLandmarks()?.size ?: 0} | landmarks: ${frameLandmarks?.size ?: 0}")
                        latch.countDown()
                    }

                    handsDetector.send(frame)
                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

                    val landmarks = frameLandmarks
                    if (landmarks != null) {
                        framesWithHand++
                        // Always store for overlay — skeleton shows on every frame with a hand
                        detectedLfs.add(LandmarkFrameData(frameIdx * FRAME_INTERVAL_MS, landmarks))
                        // Also run matching for the final prediction
                        val match = findBestMatch(normalize(landmarks))
                        Log.v(TAG, "Frame $frameIdx — match: $match")
                        if (match != null) detections.add(match)
                    }
                    frame.recycle()
                    frameIdx++
                    // Update bar only at milestones (~10 times total) — avoids UI overhead
                    if (frameIdx % milestone == 0) {
                        val pct = (frameIdx * 100) / totalFrames
                        onProgress(pct)
                    }
                }   // end for (frame in frames)
                Log.d(TAG, "Resumen: $frameIdx frames | $framesWithHand con mano | ${detections.size} detecciones")
                Log.d(TAG, "Detecciones: $detections")
            } finally {
                handsDetector.close()
                Log.d(TAG, "Hands cerrado")
            }

            return if (detections.isEmpty()) {
                Log.w(TAG, "RESULTADO: sin detecciones")
                Triple("No se detectó ninguna seña", detectedLfs, dims)
            } else {
                val winner = detections.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key ?: "No detectado"
                Log.d(TAG, "RESULTADO FINAL: $winner")
                Triple(winner, detectedLfs, dims)
            }
        }

        // ── 1. Database loading ───────────────────────────────────────────
        // Mirrors load_sign_from_csv() + load_all_signs() in Colab.
        private fun loadDatabase(): Boolean {
            signDatabase.clear()
            val assetMgr = applicationContext.assets
            val folder   = "signs_database"

            val files = try {
                assetMgr.list(folder) ?: emptyArray()
            } catch (e: Exception) {
                return false
            }

            for (filename in files) {
                if (!filename.endsWith(".csv")) continue
                try {
                    val text  = assetMgr.open("$folder/$filename").bufferedReader().readText()
                    val lines = text.lines()

                    // Parse label from first comment: "# Label: X"
                    val label = lines.firstOrNull { it.startsWith("#") }
                        ?.substringAfter(":")?.trim() ?: continue

                    val dataLines = lines.filter { !it.startsWith("#") && it.isNotBlank() }
                    if (dataLines.isEmpty()) continue

                    val header    = dataLines[0].split(",").map { it.trim() }
                    val sampleCol = header.indexOf("sample")
                    val handCol   = header.indexOf("hand_type")
                    val lmCol     = header.indexOf("landmark_id")
                    val xCol      = header.indexOf("x_norm")
                    val yCol      = header.indexOf("y_norm")
                    val zCol      = header.indexOf("z_norm")

                    if (listOf(sampleCol, handCol, xCol, yCol, zCol).any { it < 0 }) continue

                    val maxCol = maxOf(sampleCol, handCol, lmCol, xCol, yCol, zCol)
                    val rows   = dataLines.drop(1)
                        .filter  { it.split(",").size > maxCol }
                        .map     { it.split(",") }

                    // Group by hand+sample; each group must have exactly 21 rows
                    rows.groupBy { "${it[handCol].trim()}_${it[sampleCol].trim()}" }
                        .values
                        .filter  { it.size == 21 }
                        .forEach { sampleRows ->
                            // Sort by landmark_id for deterministic ordering
                            val sorted = sampleRows.sortedBy {
                                it.getOrNull(lmCol)?.trim()?.toIntOrNull() ?: 0
                            }
                            val coords = Array(21) { i ->
                                floatArrayOf(
                                    sorted[i][xCol].trim().toFloat(),
                                    sorted[i][yCol].trim().toFloat(),
                                    sorted[i][zCol].trim().toFloat()
                                )
                            }
                            signDatabase.getOrPut(label) { mutableListOf() }.add(coords)
                        }
                } catch (e: Exception) { /* skip malformed CSV silently */ }
            }
            return signDatabase.isNotEmpty()
        }

        // ── 2. Frame extraction (MediaMetadataRetriever – no extra dep) ───
        // Mirrors cv2.VideoCapture frame loop in Colab.
        private fun extractFrames(videoUri: Uri): List<Bitmap> {
            val frames    = mutableListOf<Bitmap>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, videoUri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0) return frames

                // Process every 100ms of video — no frame cap
                val count = (durationMs / FRAME_INTERVAL_MS).toInt()
                for (i in 0 until count) {
                    if (!isProposedRun) break
                    val raw = retriever.getFrameAtTime(
                        i * FRAME_INTERVAL_MS * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST   // nearest actual frame, not keyframe-only
                    )
                    if (raw != null) {
                        // MediaPipe requires ARGB_8888 — convert if needed
                        val argb = if (raw.config == Bitmap.Config.ARGB_8888) raw
                        else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
                        frames.add(argb)
                    }
                }
            } catch (e: Exception) {
                /* return whatever was collected */
            } finally {
                retriever.release()
            }
            return frames
        }

        // ── 3. Normalization – exact port of normalize_hand_coordinates() ─
        // norm = (value - axis_min) / (axis_max - axis_min)
        // Range clamped to 1.0 when max == min (avoids division by zero).
        private fun normalize(landmarks: List<FloatArray>): Array<FloatArray> {
            val xs = landmarks.map { it[0] }
            val ys = landmarks.map { it[1] }
            val zs = landmarks.map { it[2] }
            val xRange = (xs.max() - xs.min()).takeIf { it > 0f } ?: 1f
            val yRange = (ys.max() - ys.min()).takeIf { it > 0f } ?: 1f
            val zRange = (zs.max() - zs.min()).takeIf { it > 0f } ?: 1f
            val xMin = xs.min(); val yMin = ys.min(); val zMin = zs.min()

            return Array(landmarks.size) { i ->
                floatArrayOf(
                    (landmarks[i][0] - xMin) / xRange,
                    (landmarks[i][1] - yMin) / yRange,
                    (landmarks[i][2] - zMin) / zRange
                )
            }
        }

        // ── 5. Point-by-point match – exact port of check_match() ─────────
        // Euclidean distance per landmark; returns true if ≥ MIN_MATCHING_LANDMARKS match.
        private fun checkMatch(query: Array<FloatArray>, reference: Array<FloatArray>): Boolean {
            var matching = 0
            val n = minOf(query.size, reference.size)
            for (i in 0 until n) {
                val dx = query[i][0] - reference[i][0]
                val dy = query[i][1] - reference[i][1]
                val dz = query[i][2] - reference[i][2]
                if (sqrt(dx*dx + dy*dy + dz*dz) <= DISTANCE_THRESHOLD) matching++
            }
            return matching >= MIN_MATCHING_LANDMARKS
        }

        // ── 6. Database lookup – port of detect_sign() ────────────────────
        private fun findBestMatch(coords: Array<FloatArray>): String? {
            for ((label, samples) in signDatabase) {
                for (sample in samples) {
                    if (checkMatch(coords, sample)) return label
                }
            }
            return null
        }
    }
    // ══ END PropuestoProcessor ════════════════════════════════════════════════
}