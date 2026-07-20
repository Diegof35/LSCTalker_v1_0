package com.example.home_page_intento1

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class CategoryDetailActivity : AppCompatActivity() {

    // ══════════════════════════════════════════════════════════════
    // CLAVES DE INTENT
    // ══════════════════════════════════════════════════════════════
    companion object {
        const val EXTRA_CATEGORY_CODE    = "categoryCode"
        const val EXTRA_CATEGORY_NAME    = "categoryName"
        const val EXTRA_DRAWABLE_RES     = "drawableResId"
        const val EXTRA_WORDS            = "words"
        /** ≥ 0 → auto-open video popup AND finish() when popup closes (from SearchActivity). */
        const val EXTRA_AUTO_PLAY_INDEX  = "autoPlayIndex"
    }

    // ══════════════════════════════════════════════════════════════
    // COMPONENTES DE UI – pantalla de categoría
    // ══════════════════════════════════════════════════════════════
    private lateinit var imgCategoryHero:        ImageView
    private lateinit var txtCategoryDetailName:  TextView
    private lateinit var txtCategoryDetailCount: TextView
    private lateinit var btnBackCategory:        ImageButton
    private lateinit var containerCategoryWords: LinearLayout

    // ══════════════════════════════════════════════════════════════
    // COMPONENTES DE UI – popup de vídeo
    // ══════════════════════════════════════════════════════════════
    private lateinit var videoPopupContainer:  FrameLayout
    private lateinit var playerView:           PlayerView
    private lateinit var progressVideoLoading: ProgressBar
    private lateinit var btnPlayPause:         ImageButton
    private lateinit var btnVideoSpeed:        Button
    private lateinit var btnVideoClose:        ImageButton   // FIX 1.2: ImageButton
    private lateinit var seekBarVideo:         SeekBar

    // ══════════════════════════════════════════════════════════════
    // ESTADO DEL REPRODUCTOR
    // ══════════════════════════════════════════════════════════════
    private var exoPlayer: ExoPlayer? = null

    private val speeds      = floatArrayOf(1.0f, 0.75f, 0.5f)
    private val speedLabels = arrayOf("x 1.0", "x 0.75", "x 0.5")
    private var speedIndex  = 0

    private val seekHandler      = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null
    private val playPauseHandler = Handler(Looper.getMainLooper())
    private val PLAY_PAUSE_HIDE_DELAY_MS = 2000L

    // ══════════════════════════════════════════════════════════════
    // DATOS RECIBIDOS VÍA INTENT
    // ══════════════════════════════════════════════════════════════
    private var categoryCode:  String     = ""
    private var categoryName:  String     = ""
    private var drawableResId: Int        = 0
    private var words:         List<String> = emptyList()
    private var autoPlayIndex: Int        = -1

    /**
     * TRUE when launched from SearchActivity (EXTRA_AUTO_PLAY_INDEX ≥ 0).
     * In that case, closing the popup calls finish() so the user
     * returns to SearchActivity, not the word list.
     */
    private var launchedForDirectPlay = false

    // ══════════════════════════════════════════════════════════════
    // CICLO DE VIDA
    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_detail)

        readIntent()
        initializeViews()
        populateBanner()
        buildWordList()
        setupVideoPopup()
        setupNavigation()
        if (autoPlayIndex >= 0) showVideoPopup(autoPlayIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    override fun onBackPressed() {
        if (videoPopupContainer.visibility == View.VISIBLE) {
            hideVideoPopup()
        } else {
            super.onBackPressed()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LECTURA DEL INTENT
    // ══════════════════════════════════════════════════════════════
    private fun readIntent() {
        categoryCode  = intent.getStringExtra(EXTRA_CATEGORY_CODE)  ?: ""
        categoryName  = intent.getStringExtra(EXTRA_CATEGORY_NAME)  ?: ""
        drawableResId = intent.getIntExtra(EXTRA_DRAWABLE_RES, 0)
        words         = intent.getStringArrayListExtra(EXTRA_WORDS)  ?: emptyList()
        autoPlayIndex = intent.getIntExtra(EXTRA_AUTO_PLAY_INDEX, -1)

        // FIX 2.3: when launched just for direct play, finish after popup closes
        launchedForDirectPlay = autoPlayIndex >= 0
    }

    // ══════════════════════════════════════════════════════════════
    // INICIALIZACIÓN DE VISTAS
    // ══════════════════════════════════════════════════════════════
    private fun initializeViews() {
        imgCategoryHero        = findViewById(R.id.imgCategoryHero)
        txtCategoryDetailName  = findViewById(R.id.txtCategoryDetailName)
        txtCategoryDetailCount = findViewById(R.id.txtCategoryDetailCount)
        btnBackCategory        = findViewById(R.id.btnBackCategory)
        containerCategoryWords = findViewById(R.id.containerCategoryWords)

        videoPopupContainer    = findViewById(R.id.videoPopupContainer)
        playerView             = findViewById(R.id.playerView)
        progressVideoLoading   = findViewById(R.id.progressVideoLoading)
        btnPlayPause           = findViewById(R.id.btnPlayPause)
        btnVideoSpeed          = findViewById(R.id.btnVideoSpeed)
        btnVideoClose          = findViewById(R.id.btnVideoClose)  // ImageButton
        seekBarVideo           = findViewById(R.id.seekBarVideo)
    }

    // ══════════════════════════════════════════════════════════════
    // BANNER
    // ══════════════════════════════════════════════════════════════
    private fun populateBanner() {
        txtCategoryDetailName.text  = categoryName
        txtCategoryDetailCount.text = "${words.size} señas"
        if (drawableResId != 0) {
            imgCategoryHero.setImageResource(drawableResId)
            // FIX 4: clip hero image to rounded rect directly on the ImageView.
            // Parent FrameLayout also clips, but this ensures correct shape
            // regardless of image content or transparency.
            imgCategoryHero.setBackgroundColor(0xFFF0F0F0.toInt())
            imgCategoryHero.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpToPx(12).toFloat())
                }
            }
            imgCategoryHero.clipToOutline = true
        }
        btnBackCategory.setOnClickListener { finish() }
    }

    // ══════════════════════════════════════════════════════════════
    // LISTA DE SEÑAS
    // ══════════════════════════════════════════════════════════════
    private fun buildWordList() {
        containerCategoryWords.removeAllViews()
        words.forEachIndexed { index, displayName ->
            containerCategoryWords.addView(
                createCategoryWordRow(index, displayName, drawableResId)
            )
        }
    }

    private fun createCategoryWordRow(index: Int, displayName: String, drawableRes: Int): View {

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val pill = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(66)
            ).apply { marginEnd = dpToPx(8) }
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(76), dpToPx(8))
            setBackgroundResource(R.drawable.icon_sign_item_background)
            gravity     = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        val numTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            text     = "$index."
            textSize = 15f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }

        val wordTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dpToPx(8) }
            text     = displayName
            textSize = 15f
            setTextColor(resources.getColor(android.R.color.black, theme))
        }

        // FIX 3+4: clip + background applied directly on ImageView via ViewOutlineProvider.
        // Consistent rounded-corner shape regardless of image transparency.
        val thumb = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(72), dpToPx(58), Gravity.END or Gravity.CENTER_VERTICAL)
            if (drawableRes != 0) setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF0F0F0.toInt())   // Fix 3: neutral bg behind image
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpToPx(8).toFloat())
                }
            }
            clipToOutline = true
        }

        pill.addView(numTv)
        pill.addView(wordTv)

        // Tap row → open video popup for this word
        pill.setOnClickListener {
            // Register play in RecentSignsManager
            val imageName = getSignImageResName(displayName, categoryCode)
            RecentSignsManager.addAndSave(
                applicationContext,
                RecentSignsManager.RecentSignEntry(
                    displayName   = displayName,
                    categoryCode  = categoryCode,
                    categoryIndex = index,
                    categoryName  = categoryName,
                    imageResName  = imageName
                )
            )
            showVideoPopup(index)
        }

        container.addView(pill)
        container.addView(thumb)
        return container
    }

    // ══════════════════════════════════════════════════════════════
    // SETUP DEL POPUP
    // ══════════════════════════════════════════════════════════════
    private fun setupVideoPopup() {
        // FIX 1.2: btnVideoClose is now ImageButton → click still works identically
        btnVideoClose.setOnClickListener { hideVideoPopup() }

        btnVideoSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            exoPlayer?.setPlaybackSpeed(speeds[speedIndex])
            btnVideoSpeed.text = speedLabels[speedIndex]
        }

        btnPlayPause.setOnClickListener {
            exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            schedulePlayPauseHide()
        }

        playerView.setOnClickListener { showPlayPauseButton() }

        seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = exoPlayer?.duration ?: return
                    if (dur > 0) exoPlayer?.seekTo(progress * dur / 1000L)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                seekHandler.removeCallbacksAndMessages(null)
            }
            override fun onStopTrackingTouch(bar: SeekBar) { startSeekBarUpdates() }
        })
    }

    // ══════════════════════════════════════════════════════════════
    // MOSTRAR / OCULTAR POPUP
    // ══════════════════════════════════════════════════════════════
    private fun showVideoPopup(wordIndex: Int) {
        videoPopupContainer.visibility  = View.VISIBLE
        playerView.visibility           = View.INVISIBLE
        btnPlayPause.visibility         = View.GONE
        progressVideoLoading.visibility = View.VISIBLE

        speedIndex             = 0
        btnVideoSpeed.text     = speedLabels[0]
        seekBarVideo.progress  = 0

        DriveVideoRepository.getVideoUrl(categoryCode, wordIndex) { url ->
            if (url == null) {
                hideVideoPopup()
                Toast.makeText(this,
                    "No se pudo cargar el vídeo.\nVerifica la conexión o el API key.",
                    Toast.LENGTH_LONG).show()
                return@getVideoUrl
            }
            setupPlayer(url)
        }
    }

    private fun hideVideoPopup() {
        videoPopupContainer.visibility = View.GONE
        releasePlayer()
        // FIX 2.3: if launched directly from SearchActivity, return to it
        if (launchedForDirectPlay) finish()
    }

    // ══════════════════════════════════════════════════════════════
    // EXOPLAYER
    // ══════════════════════════════════════════════════════════════
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupPlayer(streamUrl: String) {
        releasePlayer()

        val dsFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("LSC-Talker/1.0 (Android)")

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()

        playerView.player = exoPlayer

        exoPlayer!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        progressVideoLoading.visibility = View.GONE
                        playerView.visibility           = View.VISIBLE
                        seekBarVideo.max                = 1000
                        startSeekBarUpdates()
                        showPlayPauseButton()
                    }
                    Player.STATE_ENDED -> {
                        exoPlayer?.seekTo(0)
                        exoPlayer?.pause()
                        updatePlayPauseIcon(false)
                        seekBarVideo.progress = 0
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }
        })

        exoPlayer!!.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
        exoPlayer!!.prepare()
        exoPlayer!!.play()
    }

    private fun releasePlayer() {
        seekHandler.removeCallbacksAndMessages(null)
        seekRunnable = null
        playPauseHandler.removeCallbacksAndMessages(null)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    // ══════════════════════════════════════════════════════════════
    // SEEK BAR – actualización periódica
    // ══════════════════════════════════════════════════════════════
    private fun startSeekBarUpdates() {
        seekHandler.removeCallbacksAndMessages(null)
        seekRunnable = object : Runnable {
            override fun run() {
                val p = exoPlayer
                if (p != null && videoPopupContainer.visibility == View.VISIBLE) {
                    val dur = p.duration
                    if (dur > 0) seekBarVideo.progress = (p.currentPosition * 1000L / dur).toInt()
                    seekHandler.postDelayed(this, 200)
                }
            }
        }
        seekHandler.post(seekRunnable!!)
    }

    // ══════════════════════════════════════════════════════════════
    // ICONO PLAY / PAUSA
    // ══════════════════════════════════════════════════════════════
    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.icon_ic_pause else R.drawable.icon_ic_play
        )
    }

    private fun showPlayPauseButton() {
        updatePlayPauseIcon(exoPlayer?.isPlaying ?: false)
        btnPlayPause.visibility = View.VISIBLE
        schedulePlayPauseHide()
    }

    private fun schedulePlayPauseHide() {
        playPauseHandler.removeCallbacksAndMessages(null)
        playPauseHandler.postDelayed(
            { btnPlayPause.visibility = View.GONE },
            PLAY_PAUSE_HIDE_DELAY_MS
        )
    }

    // ══════════════════════════════════════════════════════════════
    // IMAGEN POR SEÑA – equal logic as SearchActivity
    // ══════════════════════════════════════════════════════════════
    private fun getSignImageResName(displayName: String, catCode: String): String = when (displayName) {
        "Pera"   -> "item_pear"
        "Patilla"-> "item_watermelon"
        "Tolima" -> "item_tolima"
        else     -> categoryCodeToTheme(catCode)
    }

    private fun categoryCodeToTheme(code: String) = when (code) {
        "00"  -> "theme_essential_words"
        "01"  -> "theme_emotions"
        "02"  -> "theme_departments"
        "03"  -> "theme_fruits"
        "04"  -> "theme_family"
        "05"  -> "theme_house"
        "06"  -> "theme_time"
        "07"  -> "theme_clothes"
        "08"  -> "theme_food"
        "09"  -> "theme_characteristics"
        "10"  -> "theme_colors"
        "11"  -> "theme_animals"
        "12"  -> "theme_verbs"
        else  -> "theme_essential_words"
    }

    // ══════════════════════════════════════════════════════════════
    // NAVEGACIÓN INFERIOR
    // ══════════════════════════════════════════════════════════════
    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btnDetailHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnDetailVideos).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnDetailLearn).setOnClickListener {
            startActivity(Intent(this, LearnActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnDetailProfile).setOnClickListener {
            Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // UTILIDADES
    // ══════════════════════════════════════════════════════════════
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}