package com.example.home_page_intento1

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    // ── UI principal ──────────────────────────────────────────────
    private lateinit var imgSearchIcon:          ImageView
    private lateinit var edtSearch:              EditText
    private lateinit var imgBanner:              ImageView
    private lateinit var btnTryNow:              ImageButton
    private lateinit var btnHome:                ImageButton
    private lateinit var btnVideos:              ImageButton
    private lateinit var btnLearn:               ImageButton
    private lateinit var btnProfile:             ImageButton
    private lateinit var containerRecentSigns:   LinearLayout
    private lateinit var containerThemes:        LinearLayout

    // ── Video popup ───────────────────────────────────────────────
    private lateinit var homeVideoPopupContainer:  FrameLayout
    private lateinit var homePlayerView:           PlayerView
    private lateinit var homeProgressVideoLoading: ProgressBar
    private lateinit var homeBtnPlayPause:         ImageButton
    private lateinit var homeBtnVideoSpeed:        Button
    private lateinit var homeBtnVideoClose:        ImageButton
    private lateinit var homeSeekBarVideo:         SeekBar

    // ── ExoPlayer ─────────────────────────────────────────────────
    private var homeExoPlayer: ExoPlayer? = null
    private val homeSpeeds      = floatArrayOf(1.0f, 0.75f, 0.5f)
    private val homeSpeedLabels = arrayOf("x 1.0", "x 0.75", "x 0.5")
    private var homeSpeedIndex  = 0
    private val homeSeekHandler      = Handler(Looper.getMainLooper())
    private var homeSeekRunnable: Runnable? = null
    private val homePlayPauseHandler = Handler(Looper.getMainLooper())
    private val PLAY_PAUSE_HIDE_MS   = 2000L

    // ── FIX 3: LruCache for bitmap compression ────────────────────
    private val bitmapCache: LruCache<Int, Bitmap> =
        object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
        }

    // ── Word counts per category (for themes subtitle) ────────────
    private val categoryWordCounts = mapOf(
        "00" to 49, "01" to 35, "02" to 59, "03" to 23, "04" to 52,
        "05" to 36, "06" to 42, "07" to 28, "08" to 28, "09" to 21,
        "10" to 17, "11" to 44, "12" to 20
    )

    // ── Defaults (first launch) ───────────────────────────────────
    private val defaultRecentSigns = listOf(
        RecentSignsManager.RecentSignEntry("Pera",    "03", 19, "Frutas",                     "item_pear"),
        RecentSignsManager.RecentSignEntry("Patilla", "03", 18, "Frutas",                     "item_watermelon"),
        RecentSignsManager.RecentSignEntry("Tolima",  "02", 51, "Departamentos y Municipios", "item_tolima"),
        RecentSignsManager.RecentSignEntry("Piña",    "03", 20, "Frutas",                     "theme_fruits")
    )

    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RecentSignsManager.load(applicationContext)
        initializeViews()
        setupVideoPopup()
        // FIX 3: pre-warm cache on background thread
        prewarmBitmapCache()
        buildRecentSignsCarousel()
        buildThemesCarousel()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        edtSearch.setText("")
        edtSearch.clearFocus()
        buildRecentSignsCarousel()
        buildThemesCarousel()
    }

    override fun onDestroy() { super.onDestroy(); releaseHomePlayer() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (homeVideoPopupContainer.visibility == View.VISIBLE) hideHomeVideoPopup()
        else { super.onBackPressed() }
    }

    // ── Init ──────────────────────────────────────────────────────
    private fun initializeViews() {
        imgSearchIcon = findViewById(R.id.imgSearchIcon)
        edtSearch     = findViewById(R.id.edtSearch)
        imgBanner     = findViewById(R.id.imgBanner)
        btnTryNow     = findViewById(R.id.btnTryNow)
        btnHome       = findViewById(R.id.btnHome)
        btnVideos     = findViewById(R.id.btnVideos)
        btnLearn      = findViewById(R.id.btnLearn)
        btnProfile    = findViewById(R.id.btnProfile)
        containerRecentSigns = findViewById(R.id.containerRecentSigns)
        containerThemes      = findViewById(R.id.containerThemes)
        homeVideoPopupContainer  = findViewById(R.id.homeVideoPopupContainer)
        homePlayerView           = findViewById(R.id.homePlayerView)
        homeProgressVideoLoading = findViewById(R.id.homeProgressVideoLoading)
        homeBtnPlayPause         = findViewById(R.id.homeBtnPlayPause)
        homeBtnVideoSpeed        = findViewById(R.id.homeBtnVideoSpeed)
        homeBtnVideoClose        = findViewById(R.id.homeBtnVideoClose)
        homeSeekBarVideo         = findViewById(R.id.homeSeekBarVideo)
    }

    // ── Listeners ─────────────────────────────────────────────────
    private fun setupListeners() {
        imgSearchIcon.setOnClickListener { launchSearch(edtSearch.text.toString().trim()) }
        edtSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                launchSearch(v.text.toString().trim()); true
            } else false
        }
        btnTryNow.setOnClickListener { startActivity(Intent(this, TranslationActivity::class.java)) }
        btnHome.setOnClickListener    { /* already here */ }
        btnVideos.setOnClickListener  { startActivity(Intent(this, SearchActivity::class.java)) }
        btnLearn.setOnClickListener   { startActivity(Intent(this, LearnActivity::class.java)) }
        btnProfile.setOnClickListener { Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show() }
    }

    private fun launchSearch(query: String) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(edtSearch.windowToken, 0)
        val intent = Intent(this, SearchActivity::class.java)
        if (query.isNotBlank()) intent.putExtra(SearchActivity.EXTRA_INITIAL_QUERY, query)
        startActivity(intent)
    }

    // ══════════════════════════════════════════════════════════════
    // CAROUSEL – SEÑAS RECIENTES
    // FIX 2: FrameLayout as clip container (background + clipToOutline)
    //         ImageView fills the entire container → no more "original format" rendering
    // ══════════════════════════════════════════════════════════════
    private fun buildRecentSignsCarousel() {
        containerRecentSigns.removeAllViews()
        val entries = if (RecentSignsManager.list.isEmpty()) defaultRecentSigns
        else RecentSignsManager.list.take(RecentSignsManager.MAX_RECENT)
        entries.forEach { containerRecentSigns.addView(createRecentSignItem(it)) }
    }

    private fun createRecentSignItem(entry: RecentSignsManager.RecentSignEntry): View {
        val imgRes = resources.getIdentifier(entry.imageResName, "drawable", packageName)
            .takeIf { it != 0 } ?: R.drawable.theme_essential_words

        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(100), dpToPx(130))
                .apply { marginEnd = dpToPx(16) }
            isClickable = true; isFocusable = true
        }

        // FIX 3 (updated): clip applied directly on ImageView via ViewOutlineProvider oval.
        // Guarantees true circle regardless of image content (no dependency on background drawable).
        val photo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(100), dpToPx(100), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setImageBitmap(getCachedBitmap(imgRes, dpToPx(100), dpToPx(100)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF0F0F0.toInt())   // Fix 3: neutral bg for transparent images
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        val label = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            text          = entry.displayName
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize      = 13f
            setTextColor(resources.getColor(android.R.color.black, theme))
        }

        outer.addView(photo)
        outer.addView(label)
        outer.setOnClickListener { showHomeVideoPopup(entry) }
        return outer
    }

    // ══════════════════════════════════════════════════════════════
    // CAROUSEL – TEMAS RECIENTES
    // FIX 2: rounded-corner FrameLayout clip container
    // FIX 3: bitmap cache for theme images
    // ══════════════════════════════════════════════════════════════
    private fun buildThemesCarousel() {
        containerThemes.removeAllViews()
        val cats: List<RecentSignsManager.RecentSignEntry> =
            if (RecentSignsManager.list.isEmpty()) listOf(
                RecentSignsManager.RecentSignEntry("Departamentos y Municipios","02",0,"Departamentos y Municipios","theme_departments"),
                RecentSignsManager.RecentSignEntry("Frutas",                    "03",0,"Frutas",                    "theme_fruits"),
                RecentSignsManager.RecentSignEntry("Alimentos y Compras",       "08",0,"Alimentos y Compras",       "theme_food")
            ) else RecentSignsManager.uniqueCategories()
        cats.forEach { containerThemes.addView(createThemeItem(it)) }
    }

    private fun createThemeItem(entry: RecentSignsManager.RecentSignEntry): View {
        // Always use the category theme drawable (never sign-specific item_xxx)
        val themeResName = categoryCodeToThemeName(entry.categoryCode)
        val themeImgRes  = resources.getIdentifier(themeResName, "drawable", packageName)
            .takeIf { it != 0 } ?: R.drawable.theme_essential_words

        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(160), dpToPx(170))
                .apply { marginEnd = dpToPx(16) }
            isClickable = true; isFocusable = true
        }

        // FIX 3 (updated): clip applied directly on ImageView via ViewOutlineProvider rounded rect.
        // Guarantees consistent rounded corners regardless of image content.
        val photo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(160), dpToPx(140))
            setImageBitmap(getCachedBitmap(themeImgRes, dpToPx(160), dpToPx(140)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF0F0F0.toInt())   // Fix 3: neutral bg for transparent images
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpToPx(12).toFloat())
                }
            }
            clipToOutline = true
        }

        val wordCount = categoryWordCounts[entry.categoryCode] ?: 0
        val subtitle = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(17) }
            text = "$wordCount señas"; textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 9f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
        val name = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(2) }
            text = entry.categoryName; textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.black, theme))
        }

        outer.addView(photo); outer.addView(subtitle); outer.addView(name)
        // FIX 1: Navigate directly to the category word list (not SearchActivity)
        outer.setOnClickListener {
            startActivity(Intent(this, CategoryDetailActivity::class.java).apply {
                putExtra(CategoryDetailActivity.EXTRA_CATEGORY_CODE, entry.categoryCode)
                putExtra(CategoryDetailActivity.EXTRA_CATEGORY_NAME, entry.categoryName)
                putExtra(CategoryDetailActivity.EXTRA_DRAWABLE_RES, themeImgRes)
                putStringArrayListExtra(CategoryDetailActivity.EXTRA_WORDS,
                    ArrayList(wordsForCategory(entry.categoryCode)))
            })
        }
        return outer
    }

    // ── Video popup ───────────────────────────────────────────────
    private fun setupVideoPopup() {
        homeBtnVideoClose.setOnClickListener { hideHomeVideoPopup() }
        homeBtnVideoSpeed.setOnClickListener {
            homeSpeedIndex = (homeSpeedIndex + 1) % homeSpeeds.size
            homeExoPlayer?.setPlaybackSpeed(homeSpeeds[homeSpeedIndex])
            homeBtnVideoSpeed.text = homeSpeedLabels[homeSpeedIndex]
        }
        homeBtnPlayPause.setOnClickListener {
            homeExoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHomePlayPauseHide()
        }
        homePlayerView.setOnClickListener { showHomePlayPauseButton() }
        homeSeekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { val dur = homeExoPlayer?.duration ?: return; if (dur > 0) homeExoPlayer?.seekTo(progress * dur / 1000L) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { homeSeekHandler.removeCallbacksAndMessages(null) }
            override fun onStopTrackingTouch(bar: SeekBar)  { startHomeSeekBarUpdates() }
        })
    }

    private fun showHomeVideoPopup(entry: RecentSignsManager.RecentSignEntry) {
        RecentSignsManager.addAndSave(applicationContext, entry)
        homeVideoPopupContainer.visibility  = View.VISIBLE
        homePlayerView.visibility           = View.INVISIBLE
        homeBtnPlayPause.visibility         = View.GONE
        homeProgressVideoLoading.visibility = View.VISIBLE
        homeSpeedIndex = 0; homeBtnVideoSpeed.text = homeSpeedLabels[0]; homeSeekBarVideo.progress = 0
        DriveVideoRepository.getVideoUrl(entry.categoryCode, entry.categoryIndex) { url ->
            if (url == null) { hideHomeVideoPopup(); Toast.makeText(this,"No se pudo cargar el vídeo.",Toast.LENGTH_LONG).show(); return@getVideoUrl }
            setupHomePlayer(url)
        }
    }

    private fun hideHomeVideoPopup() { homeVideoPopupContainer.visibility = View.GONE; releaseHomePlayer() }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupHomePlayer(streamUrl: String) {
        releaseHomePlayer()
        val dsFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000).setUserAgent("LSC-Talker/1.0 (Android)")
        homeExoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory)).build()
        homePlayerView.player = homeExoPlayer
        homeExoPlayer!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { homeProgressVideoLoading.visibility = View.GONE; homePlayerView.visibility = View.VISIBLE; homeSeekBarVideo.max = 1000; startHomeSeekBarUpdates(); showHomePlayPauseButton() }
                    Player.STATE_ENDED -> { homeExoPlayer?.seekTo(0); homeExoPlayer?.pause(); updateHomePlayPauseIcon(false); homeSeekBarVideo.progress = 0 }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateHomePlayPauseIcon(isPlaying) }
        })
        homeExoPlayer!!.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
        homeExoPlayer!!.prepare(); homeExoPlayer!!.play()
    }

    private fun releaseHomePlayer() {
        homeSeekHandler.removeCallbacksAndMessages(null); homeSeekRunnable = null
        homePlayPauseHandler.removeCallbacksAndMessages(null)
        homeExoPlayer?.stop(); homeExoPlayer?.release(); homeExoPlayer = null
    }

    private fun startHomeSeekBarUpdates() {
        homeSeekHandler.removeCallbacksAndMessages(null)
        homeSeekRunnable = object : Runnable {
            override fun run() {
                val p = homeExoPlayer
                if (p != null && homeVideoPopupContainer.visibility == View.VISIBLE) {
                    val dur = p.duration; if (dur > 0) homeSeekBarVideo.progress = (p.currentPosition * 1000L / dur).toInt()
                    homeSeekHandler.postDelayed(this, 200)
                }
            }
        }
        homeSeekHandler.post(homeSeekRunnable!!)
    }

    private fun updateHomePlayPauseIcon(isPlaying: Boolean) { homeBtnPlayPause.setImageResource(if (isPlaying) R.drawable.icon_ic_pause else R.drawable.icon_ic_play) }
    private fun showHomePlayPauseButton() { updateHomePlayPauseIcon(homeExoPlayer?.isPlaying ?: false); homeBtnPlayPause.visibility = View.VISIBLE; scheduleHomePlayPauseHide() }
    private fun scheduleHomePlayPauseHide() { homePlayPauseHandler.removeCallbacksAndMessages(null); homePlayPauseHandler.postDelayed({ homeBtnPlayPause.visibility = View.GONE }, PLAY_PAUSE_HIDE_MS) }

    // ── FIX 3: Bitmap helpers ─────────────────────────────────────
    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        val (srcH, srcW) = opts.outHeight to opts.outWidth
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2; val halfW = srcW / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun decodeSampledBitmap(resId: Int, reqW: Int, reqH: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, opts)
        opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(resources, resId, opts)
    }

    private fun getCachedBitmap(resId: Int, reqW: Int, reqH: Int): Bitmap {
        val key = resId * 1000 + reqW   // unique per resource+size combination
        return bitmapCache.get(key) ?: run {
            val bmp = decodeSampledBitmap(resId, reqW, reqH)
            bitmapCache.put(key, bmp); bmp
        }
    }

    private fun prewarmBitmapCache() {
        Thread {
            // Pre-load theme images (used for both carousels)
            val themeRes = listOf(
                R.drawable.theme_essential_words, R.drawable.theme_emotions,
                R.drawable.theme_departments, R.drawable.theme_fruits,
                R.drawable.theme_family, R.drawable.theme_house,
                R.drawable.theme_time, R.drawable.theme_clothes,
                R.drawable.theme_food, R.drawable.theme_characteristics,
                R.drawable.theme_colors, R.drawable.theme_animals,
                R.drawable.theme_verbs
            )
            themeRes.forEach { getCachedBitmap(it, dpToPx(160), dpToPx(140)) }
            // Pre-load sign-specific items for circles
            listOf(R.drawable.item_pear, R.drawable.item_watermelon, R.drawable.item_tolima)
                .forEach { getCachedBitmap(it, dpToPx(100), dpToPx(100)) }
        }.start()
    }

    // ── Helpers ───────────────────────────────────────────────────
    // ── Word lists per category (mirrors SearchActivity data) ─────
    private val categoriesRawData = listOf(
        Triple("00","Palabras Esenciales", listOf("Aburrido","Anciano / Viejo","Asistir","Bien o Mal","Buenas Noches","Buenas Tardes","Buenos Días","Chao - Adiós","¿Cómo Está?","Comunicación","Conocer","Ellos - Ellas","Enseñar - Enseñarme","Entender","Estudiante","Hasta Mañana","Hipoacúsico (A)","Hola (2 Formas)","Hombre - Mujer","Invitar - Invítame","Joven","Lengua de Seña","Lo Siento","Maduro","Mucho Gusto","Nosotros","Oyente(s)","Perdón","Permiso","Permiso (Trabajo)","Persona - Personas","Poco a Poco","Por Favor","Por la Mañana","Por la Noche","Por la Tarde","Preguntar - Preguntarme","Presentar","Profesor (A)","Quiero","Regular","Saber","Saludo (2 Formas)","Sordo (A)","Todos","Tú","Usted - Ustedes","Yo","Él")),
        Triple("01","Emociones y Sentimientos", listOf("Alegre","Analizar","Averiguar","Burlar","Caer","Calma","Contento (A)","Delicado","Deprimido","Desesperado","Difundir","Feliz","Fracaso","Frustrado","Furioso","Ignorar","Inconstante","Inseguro","Interesar","Investigar","Llevar","Mentira","Molestar","Necesitar","Negativo","Nervioso","Poder","Positivo","Preocupado","Prohibir","Seguro","Sensible","Traer","Triste","Visitar")),
        Triple("02","Departamentos y Municipios", listOf("Amazona","Antioquia","Arauca","Armenia","Atlántico","Barranquilla","Bogotá","Bolívar","Boyacá","Bucaramanga","Caldas","Cali","Capital","Caquetá","Cartagena","Casanare","Cauca","Cesar","Chocó","Colombia","Cúcuta","Cundinamarca","Departamento","Florencia","Guajira","Guaviare","Huila","Ibagué","Leticia","Magdalena","Manizales","Medellín","Meta","Mocoa","Municipio o Pueblo","Nariño","Neiva","Norte de Santander","Pasto","País","Pereira","Popayán","Putumayo","Quibdó","Quindío","Riohacha","Risaralda","Santa Marta","Santander","Sincelejo","Sucre","Tolima","Tunja","Valle del Cauca","Valledupar","Vaupés","Vichada","Villavicencio","Yopal")),
        Triple("03","Frutas", listOf("Aguacate","Banano","Coco","Curuba","Fresa","Granadilla","Guanábana","Guayaba","Limonada","Lulo","Mandarina","Mango","Manzana Roja - Verde","Maracuyá","Melón","Mora","Naranja","Papaya","Patilla","Pera","Piña","Tomate de Árbol","Uva Negro-Rojo-Verde")),
        Triple("04","Familia y Relaciones", listOf("Cuñado (A) (2 Formas)","Abuelo - Abuela","Amante","Amar","Bebé","Beso - Besar","Bonita (O)","Casado (A)","Castigar","Consentido","Coqueto (A)","Desobediente","Divorciado","Embarazada","Enamorar","Esposo (A)","Familia","Gemelos","Grande - Pequeño","Guapo (A)","Gustar","Hacer el Amor","Hermano (A)","Hijo (A)","Huérfano","Luna de Miel","Madrastra","Madrina","Mamá","Matrimonio Católico","Matrimonio Civil","Mellizos","Mirar","Nieto - Nieta","Novio (A)","Nuero (A)","Obediente","Padrastro","Padrino","Papá","Primo (A)","Separado (A)","Señorita","Sobrino (A)","Solo","Soltero (A)","Suegro (A)","Tío (A)","Unión Libre","Viajar","Viudo","Yerno (A)")),
        Triple("05","Hogar y Objetos Personales", listOf("Pisos (1-2-3)","Abrir la Puerta","Alberca","Almohada","Ascensor","Baño","Cama Grande - Pequeña","Casa","Cerrada la Puerta","Cobija","Cocina","Comedor","Cuadro","Ducha","Equipo","Escalera","Espejo","Habitación de Estudio","Habitación","Inodoro","Lámpara","Lava Manos","Lavadora","Mesa","Mesa - Armario","Nevera","Pararse","Sala","Sentarse","Silla","Silla Operativa","Sofá Pequeño - Grande","Tina","Toalla","TV","Ventana")),
        Triple("06","Tiempo y Calendario", listOf("1-2-3 Meses y Más","1-2-3 Semanas y Más","Hoy (2 Formas)","Próximo (2 Formas)","Ahora","Amanecer","Anoche","Anteayer","Antes","Atrasado","Ayer","Año","Bimestral","Cumpleaños","De Pronto","Después","Día","Fecha","Festivo","Fin de Mes","Hace Mucho Tiempo","Hace Rato","Hace Tiempo","Hasta Mañana","Hasta Pronto","Luego","Madrugada","Mañana","Mensual","Nunca","Pasado Mañana","Próximo","Quincena","Semana Santa","Siempre","Tarde","Temprano","Termina","Todavía","Todo el Día","Todos los Días","Trimestral")),
        Triple("07","Ropa y Accesorios", listOf("Blusa","Boxer Flojos Hombre","Boxer Hombre y Mujer","Boxer Lencería Mujer","Brasier","Brasileras","Buzo Flojo","Cachucha","Camisa","Camiseta","Chaqueta","Corbata","Correa","Gafas Hombre y Mujer","Medias Mallas","Medias Mujer y Hombre","Medias Tobilleras","Pantalón","Pantalón (Jeans)","Pantalón Apretado Hombre y Mujer","Pantaloneta Jeans Hombre y Mujer","Pantaloneta Mujer","Pijama Mujer y Hombre","Polo","Sombrero","Tacones","Tenis","Zapatos")),
        Triple("08","Alimentos y Compras", listOf("Aceite","Arroz","Arvejas Amarillas","Atún","Azúcar","Chocolate","Espagueti","Galletas","Gelatina","Harina de Trigo","Harina P.A.N.","Huevo","Leche","Leche Condensada","Leche en Polvo","Mantequilla","Mayonesa","Mercado","Mermelada","Palomitas de Maíz","Pan","Panela (2 Formas)","Papel Higiénico","Pasta Fideos","Sal","Salsa de Tomate","Vinagre","Yogurt")),
        Triple("09","Características y Cualidades", listOf("Activo","Alterado","Amable","Chistoso","Creído","Decente","Desobediente","Egoísta","Explosivo","Grosero (A)","Hipócrita","Humilde","Inteligente (2 Formas)","Líder","Mal Genio (A)","Mentiroso (A)","Orgulloso","Perezoso","Raro","Serio (A)","Terco")),
        Triple("10","Colores", listOf("Amarillo","Azul (Claro, Medio, Oscuro)","Blanco","Bronce","Café (Oscuro, Claro y Café)","Colores Brillantes","Colores Transparentes","Gris","Morado (2 Formas)","Naranja","Negro","Oro","Plata","Rojo (Claro y Oscuro)","Rosado (Claro, Oscuro y Rosado)","Verde (Oscuro y Claro)","Vino Tinto")),
        Triple("11","Animales", listOf("Águila","Araña","Aves","Burro","Caballo","Camello","Canguro","Cerdo","Chivas","Conejo","Cucaracha","Culebra","Elefante","Gallo","Gato","Gusano","Hipopótamo","Hormiga","Jirafa","León","Loro","Mariposa","Mono","Mosca","Oso","Oveja","Paloma","Pato","Pavo","Pavo Real","Perro","Piojos","Pollito","Pollo Gallina","Ratón","Rinoceronte","Sapo","Simio","Cisne","Tigre","Toro","Tortuga","Vaca","Zancudo")),
        Triple("12","Acciones y Verbos", listOf("Acariciar","Arrepentir","Avisar","Ayudar","Bromear (2 Formas)","Cocinar","Descansar","Dialogar","Dibujar","Escoger","Escribir","Estudiar","Lavar","Organizar","Planchar","Preparar","Probar","Regañar","Robar","Soñar"))
    )

    private fun wordsForCategory(code: String) =
        categoriesRawData.firstOrNull { it.first == code }?.third ?: emptyList()

    private fun categoryCodeToThemeName(code: String) = when (code) {
        "00" -> "theme_essential_words"; "01" -> "theme_emotions"
        "02" -> "theme_departments";     "03" -> "theme_fruits"
        "04" -> "theme_family";          "05" -> "theme_house"
        "06" -> "theme_time";            "07" -> "theme_clothes"
        "08" -> "theme_food";            "09" -> "theme_characteristics"
        "10" -> "theme_colors";          "11" -> "theme_animals"
        "12" -> "theme_verbs";           else -> "theme_essential_words"
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}