package com.example.home_page_intento1

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
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
import java.text.Normalizer
import java.util.*

class SearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEARN_MODE    = "learnMode"
        const val EXTRA_INITIAL_QUERY = "initialQuery"
    }

    // ── UI ────────────────────────────────────────────────────────
    private lateinit var edtSearch:           EditText
    private lateinit var txtSearchResults:    TextView
    private lateinit var txtThemeResults:     TextView
    private lateinit var layoutSearchResults: LinearLayout
    private lateinit var layoutTopicsHeader:  LinearLayout
    private lateinit var txtThemesExpand:     TextView
    private lateinit var scrollThemes:        HorizontalScrollView
    private lateinit var containerThemes:     LinearLayout
    private lateinit var containerTopicsGrid: LinearLayout
    private lateinit var txtSignsExpand:      TextView
    private lateinit var scrollSigns:         ScrollView
    private lateinit var containerSigns:      LinearLayout

    // ── Video popup ───────────────────────────────────────────────
    private lateinit var searchVideoPopupContainer:  FrameLayout
    private lateinit var searchPlayerView:           PlayerView
    private lateinit var searchProgressVideoLoading: ProgressBar
    private lateinit var searchBtnPlayPause:         ImageButton
    private lateinit var searchBtnVideoSpeed:        Button
    private lateinit var searchBtnVideoClose:        ImageButton
    private lateinit var searchSeekBarVideo:         SeekBar

    private var searchExoPlayer: ExoPlayer? = null
    private val searchSpeeds      = floatArrayOf(1.0f, 0.75f, 0.5f)
    private val searchSpeedLabels = arrayOf("x 1.0", "x 0.75", "x 0.5")
    private var searchSpeedIndex  = 0
    private val searchSeekHandler      = Handler(Looper.getMainLooper())
    private var searchSeekRunnable: Runnable? = null
    private val searchPlayPauseHandler = Handler(Looper.getMainLooper())
    private val PLAY_PAUSE_HIDE_MS = 2000L

    // ── LruCache ─────────────────────────────────────────────────
    private val bitmapCache: LruCache<Int, Bitmap> =
        object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
        }

    // ── State ─────────────────────────────────────────────────────
    private var isTopicsExpanded = false
    private var isSignsExpanded  = false
    private val SIGNS_COLLAPSED_DP = 260

    // ── Data ──────────────────────────────────────────────────────
    data class WordEntry(val index: Int, val displayName: String,
                         val categoryCode: String, val categoryDisplayName: String,
                         val categoryIndex: Int)
    data class CategoryEntry(val code: String, val displayName: String,
                             val drawableRes: Int, val wordCount: Int)

    private val allWords      = mutableListOf<WordEntry>()
    private val allCategories = mutableListOf<CategoryEntry>()

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

    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        initializeViews()
        initializeDatabase()
        prewarmBitmapCache()
        buildTopicsCarousel(allCategories)
        updateSignsList(allWords)
        setupSearchFunctionality()
        setupExpandCollapse()
        setupNavigation()
        setupVideoPopup()
        if (intent.getBooleanExtra(EXTRA_LEARN_MODE, false)) expandTopics()
        val q = intent.getStringExtra(EXTRA_INITIAL_QUERY)
        if (!q.isNullOrBlank()) { edtSearch.setText(q); edtSearch.setSelection(q.length); performSearch(q) }
    }

    override fun onDestroy() { super.onDestroy(); releaseSearchPlayer() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchVideoPopupContainer.visibility == View.VISIBLE) hideSearchVideoPopup()
        else super.onBackPressed()
    }

    private fun initializeViews() {
        edtSearch           = findViewById(R.id.edtSearch)
        txtSearchResults    = findViewById(R.id.txtSearchResults)
        txtThemeResults     = findViewById(R.id.txtThemeResults)
        layoutSearchResults = findViewById(R.id.layoutSearchResults)
        layoutTopicsHeader  = findViewById(R.id.layoutTopicsHeader)
        txtThemesExpand     = findViewById(R.id.txtThemesExpand)
        scrollThemes        = findViewById(R.id.scrollThemes)
        containerThemes     = findViewById(R.id.containerThemes)
        containerTopicsGrid = findViewById(R.id.containerTopicsGrid)
        txtSignsExpand      = findViewById(R.id.txtSignsExpand)
        scrollSigns         = findViewById(R.id.scrollSigns)
        containerSigns      = findViewById(R.id.containerSigns)
        searchVideoPopupContainer  = findViewById(R.id.searchVideoPopupContainer)
        searchPlayerView           = findViewById(R.id.searchPlayerView)
        searchProgressVideoLoading = findViewById(R.id.searchProgressVideoLoading)
        searchBtnPlayPause         = findViewById(R.id.searchBtnPlayPause)
        searchBtnVideoSpeed        = findViewById(R.id.searchBtnVideoSpeed)
        searchBtnVideoClose        = findViewById(R.id.searchBtnVideoClose)
        searchSeekBarVideo         = findViewById(R.id.searchSeekBarVideo)
    }

    private fun initializeDatabase() {
        categoriesRawData.forEach { (code, name, words) ->
            allCategories.add(CategoryEntry(code, name, getThemeDrawable(code), words.size))
        }
        val catIdx = categoriesRawData.associate { (code,_,words) ->
            code to words.mapIndexed { i,w -> w to i }.toMap()
        }
        val rawAll = mutableListOf<Triple<String,String,String>>()
        categoriesRawData.forEach { (code,name,words) -> words.forEach { rawAll.add(Triple(it,code,name)) } }
        rawAll.sortWith(compareBy { normalizeText(it.first) })
        rawAll.forEachIndexed { idx,(word,code,catName) ->
            allWords.add(WordEntry(idx+1, word, code, catName, catIdx[code]?.get(word) ?: 0))
        }
    }

    private fun getThemeDrawable(code: String) = when(code) {
        "00"->R.drawable.theme_essential_words; "01"->R.drawable.theme_emotions
        "02"->R.drawable.theme_departments;     "03"->R.drawable.theme_fruits
        "04"->R.drawable.theme_family;          "05"->R.drawable.theme_house
        "06"->R.drawable.theme_time;            "07"->R.drawable.theme_clothes
        "08"->R.drawable.theme_food;            "09"->R.drawable.theme_characteristics
        "10"->R.drawable.theme_colors;          "11"->R.drawable.theme_animals
        "12"->R.drawable.theme_verbs;           else->R.drawable.theme_essential_words
    }

    // ── Video popup ───────────────────────────────────────────────
    private fun setupVideoPopup() {
        searchBtnVideoClose.setOnClickListener { hideSearchVideoPopup() }
        searchBtnVideoSpeed.setOnClickListener {
            searchSpeedIndex = (searchSpeedIndex+1) % searchSpeeds.size
            searchExoPlayer?.setPlaybackSpeed(searchSpeeds[searchSpeedIndex])
            searchBtnVideoSpeed.text = searchSpeedLabels[searchSpeedIndex]
        }
        searchBtnPlayPause.setOnClickListener {
            searchExoPlayer?.let { if(it.isPlaying) it.pause() else it.play() }
            scheduleSearchPlayPauseHide()
        }
        searchPlayerView.setOnClickListener { showSearchPlayPauseButton() }
        searchSeekBarVideo.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                if(fromUser) { val d = searchExoPlayer?.duration ?: return; if(d>0) searchExoPlayer?.seekTo(p*d/1000L) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { searchSeekHandler.removeCallbacksAndMessages(null) }
            override fun onStopTrackingTouch(bar: SeekBar)  { startSearchSeekBarUpdates() }
        })
    }

    private fun showSearchVideoPopup(catCode: String, wordIdx: Int,
                                     entry: RecentSignsManager.RecentSignEntry) {
        RecentSignsManager.addAndSave(applicationContext, entry)
        searchVideoPopupContainer.visibility  = View.VISIBLE
        searchPlayerView.visibility           = View.INVISIBLE
        searchBtnPlayPause.visibility         = View.GONE
        searchProgressVideoLoading.visibility = View.VISIBLE
        searchSpeedIndex = 0; searchBtnVideoSpeed.text = searchSpeedLabels[0]
        searchSeekBarVideo.progress = 0
        DriveVideoRepository.getVideoUrl(catCode, wordIdx) { url ->
            if(url == null) {
                hideSearchVideoPopup()
                Toast.makeText(this,"No se pudo cargar el vídeo.", Toast.LENGTH_LONG).show()
                return@getVideoUrl
            }
            setupSearchPlayer(url)
        }
    }

    private fun hideSearchVideoPopup() {
        searchVideoPopupContainer.visibility = View.GONE
        releaseSearchPlayer()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupSearchPlayer(url: String) {
        releaseSearchPlayer()
        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000).setUserAgent("LSC-Talker/1.0")
        searchExoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        searchPlayerView.player = searchExoPlayer
        searchExoPlayer!!.addListener(object: Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when(state) {
                    Player.STATE_READY -> {
                        searchProgressVideoLoading.visibility = View.GONE
                        searchPlayerView.visibility = View.VISIBLE
                        searchSeekBarVideo.max = 1000
                        startSearchSeekBarUpdates(); showSearchPlayPauseButton()
                    }
                    Player.STATE_ENDED -> {
                        searchExoPlayer?.seekTo(0); searchExoPlayer?.pause()
                        updateSearchPlayPauseIcon(false); searchSeekBarVideo.progress = 0
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateSearchPlayPauseIcon(isPlaying) }
        })
        searchExoPlayer!!.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        searchExoPlayer!!.prepare(); searchExoPlayer!!.play()
    }

    private fun releaseSearchPlayer() {
        searchSeekHandler.removeCallbacksAndMessages(null); searchSeekRunnable = null
        searchPlayPauseHandler.removeCallbacksAndMessages(null)
        searchExoPlayer?.stop(); searchExoPlayer?.release(); searchExoPlayer = null
    }

    private fun startSearchSeekBarUpdates() {
        searchSeekHandler.removeCallbacksAndMessages(null)
        searchSeekRunnable = object: Runnable {
            override fun run() {
                val p = searchExoPlayer
                if(p != null && searchVideoPopupContainer.visibility == View.VISIBLE) {
                    val d = p.duration
                    if(d > 0) searchSeekBarVideo.progress = (p.currentPosition * 1000L / d).toInt()
                    searchSeekHandler.postDelayed(this, 200)
                }
            }
        }
        searchSeekHandler.post(searchSeekRunnable!!)
    }

    private fun updateSearchPlayPauseIcon(isPlaying: Boolean) {
        searchBtnPlayPause.setImageResource(
            if(isPlaying) R.drawable.icon_ic_pause else R.drawable.icon_ic_play
        )
    }
    private fun showSearchPlayPauseButton() {
        updateSearchPlayPauseIcon(searchExoPlayer?.isPlaying ?: false)
        searchBtnPlayPause.visibility = View.VISIBLE
        scheduleSearchPlayPauseHide()
    }
    private fun scheduleSearchPlayPauseHide() {
        searchPlayPauseHandler.removeCallbacksAndMessages(null)
        searchPlayPauseHandler.postDelayed({ searchBtnPlayPause.visibility = View.GONE }, PLAY_PAUSE_HIDE_MS)
    }

    // ── Search ────────────────────────────────────────────────────
    private fun setupSearchFunctionality() {
        edtSearch.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { performSearch(s.toString()) }
        })
    }

    private fun performSearch(query: String) {
        val norm = normalizeText(query)
        if(norm.isEmpty()) {
            layoutSearchResults.visibility = View.GONE
            buildTopicsCarousel(allCategories); updateSignsList(allWords); return
        }
        val mw = allWords.filter { normalizeText(it.displayName).contains(norm) }
        val mt = allCategories.filter { normalizeText(it.displayName).contains(norm) }
        txtSearchResults.text = "${mw.size} señas relacionadas con: \"$query\"."
        txtThemeResults.text  = "${mt.size} temas relacionados con: \"$query\"."
        layoutSearchResults.visibility = View.VISIBLE
        buildTopicsCarousel(if(mt.isEmpty()) allCategories else mt)
        updateSignsList(mw)
    }

    // ── Expand/collapse ───────────────────────────────────────────
    private fun setupExpandCollapse() {
        txtThemesExpand.setOnClickListener { if(isTopicsExpanded) collapseTopics() else expandTopics() }
        txtSignsExpand.setOnClickListener  { if(isSignsExpanded) collapseSigns()   else expandSigns() }
    }

    private fun expandTopics() {
        isTopicsExpanded = true
        scrollThemes.visibility        = View.GONE
        containerTopicsGrid.visibility = View.VISIBLE
        txtThemesExpand.text           = "‹"
        buildTopicsGrid(allCategories)
    }
    private fun collapseTopics() {
        isTopicsExpanded = false
        scrollThemes.visibility        = View.VISIBLE
        containerTopicsGrid.visibility = View.GONE
        txtThemesExpand.text           = "›"
    }
    private fun expandSigns() {
        isSignsExpanded = true
        scrollSigns.layoutParams = scrollSigns.layoutParams.apply {
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        txtSignsExpand.text = "‹"
    }
    private fun collapseSigns() {
        isSignsExpanded = false
        scrollSigns.layoutParams = scrollSigns.layoutParams.apply {
            height = dpToPx(SIGNS_COLLAPSED_DP)
        }
        txtSignsExpand.text = "›"
    }

    // ── Topics carousel + grid ────────────────────────────────────
    private fun buildTopicsCarousel(cats: List<CategoryEntry>) {
        containerThemes.removeAllViews()
        cats.forEach { containerThemes.addView(createCategoryCard(it)) }
    }

    private fun buildTopicsGrid(cats: List<CategoryEntry>) {
        containerTopicsGrid.removeAllViews()
        cats.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(8) }
                orientation = LinearLayout.HORIZONTAL
            }
            pair.forEach { row.addView(createCategoryCard(it, grid = true)) }
            if(pair.size == 1) {
                row.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            containerTopicsGrid.addView(row)
        }
    }

    private fun createCategoryCard(cat: CategoryEntry, grid: Boolean = false): View {
        val outer = FrameLayout(this).apply {
            layoutParams = if(grid)
                LinearLayout.LayoutParams(0, dpToPx(170), 1f).apply { marginEnd = dpToPx(8) }
            else
                LinearLayout.LayoutParams(dpToPx(160), dpToPx(170)).apply { marginEnd = dpToPx(16) }
            isClickable = true; isFocusable = true
        }

        // FIX 2+3: clip + background applied directly on ImageView via ViewOutlineProvider.
        // Consistent rounded-corner shape regardless of image content or transparency.
        // Background color ensures shape is always visible even with transparent images.
        val photo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(140)
            )
            setImageBitmap(getCachedBitmap(cat.drawableRes, dpToPx(160), dpToPx(140)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFF0F0F0.toInt())   // Fix 3: neutral bg for transparent images
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpToPx(12).toFloat())
                }
            }
            clipToOutline = true
        }

        val subtitle = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(17) }
            text = "${cat.wordCount} señas"
            textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 9f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
        val name = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(2) }
            text = cat.displayName
            textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.black, theme))
        }

        outer.addView(photo); outer.addView(subtitle); outer.addView(name)
        outer.setOnClickListener {
            startActivity(Intent(this, CategoryDetailActivity::class.java).apply {
                putExtra(CategoryDetailActivity.EXTRA_CATEGORY_CODE, cat.code)
                putExtra(CategoryDetailActivity.EXTRA_CATEGORY_NAME, cat.displayName)
                putExtra(CategoryDetailActivity.EXTRA_DRAWABLE_RES, cat.drawableRes)
                putStringArrayListExtra(CategoryDetailActivity.EXTRA_WORDS,
                    ArrayList(wordsForCategory(cat.code)))
            })
        }
        return outer
    }

    // ── Signs list ────────────────────────────────────────────────
    private fun updateSignsList(words: List<WordEntry>) {
        containerSigns.removeAllViews()
        words.forEach { containerSigns.addView(createSignItem(it)) }
    }

    private fun createSignItem(entry: WordEntry): View {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            setBackgroundResource(R.drawable.icon_sign_item_background)
            gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
        }
        val numTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = "${entry.index}."; textSize = 15f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
        val wordTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dpToPx(12) }
            text = entry.displayName; textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.black, theme))
        }
        val catTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "(${entry.categoryDisplayName})"; textSize = 12f
            setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
        row.addView(numTv); row.addView(wordTv); row.addView(catTv)
        row.setOnClickListener {
            val imgName = getSignImageResName(entry.displayName, entry.categoryCode)
            showSearchVideoPopup(
                entry.categoryCode, entry.categoryIndex,
                RecentSignsManager.RecentSignEntry(
                    entry.displayName, entry.categoryCode, entry.categoryIndex,
                    entry.categoryDisplayName, imgName
                )
            )
        }
        return row
    }

    private fun getSignImageResName(name: String, code: String) = when(name) {
        "Pera" -> "item_pear"; "Patilla" -> "item_watermelon"; "Tolima" -> "item_tolima"
        else   -> categoryCodeToThemeName(code)
    }
    private fun categoryCodeToThemeName(code: String) = when(code) {
        "00"->"theme_essential_words"; "01"->"theme_emotions"; "02"->"theme_departments"
        "03"->"theme_fruits";          "04"->"theme_family";   "05"->"theme_house"
        "06"->"theme_time";            "07"->"theme_clothes";  "08"->"theme_food"
        "09"->"theme_characteristics"; "10"->"theme_colors";   "11"->"theme_animals"
        "12"->"theme_verbs";           else->"theme_essential_words"
    }

    // ── Navigation ────────────────────────────────────────────────
    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnVideos).setOnClickListener {
            Toast.makeText(this, "Buscar señas", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btnLearn).setOnClickListener {
            startActivity(Intent(this, LearnActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnProfile).setOnClickListener {
            Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btnTryNow).setOnClickListener {
            Toast.makeText(this, "LSC Talker – próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────
    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var s = 1
        val (h, w) = opts.outHeight to opts.outWidth
        if(h > reqH || w > reqW) {
            val hh = h / 2; val hw = w / 2
            while(hh / s >= reqH && hw / s >= reqW) s *= 2
        }
        return s
    }

    private fun decodeSampledBitmap(resId: Int, reqW: Int, reqH: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, o)
        o.inSampleSize = calculateInSampleSize(o, reqW, reqH); o.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(resources, resId, o)
    }

    // ROOT FIX: Default now matches actual card size (160dp × 140dp), not 480dp.
    // Using dpToPx(480) as default caused 1440×1260px bitmaps on 3× screens
    // → UI thread stalled decoding them → freeze on expand.
    private fun getCachedBitmap(resId: Int,
                                reqW: Int = dpToPx(160),
                                reqH: Int = dpToPx(140)): Bitmap {
        val key = resId * 1000 + reqW
        return bitmapCache.get(key) ?: run {
            val b = decodeSampledBitmap(resId, reqW, reqH)
            bitmapCache.put(key, b); b
        }
    }

    // Prewarm now uses correct size — cache hits guaranteed before user expands Topics.
    private fun prewarmBitmapCache() {
        Thread {
            allCategories.forEach { getCachedBitmap(it.drawableRes, dpToPx(160), dpToPx(140)) }
        }.start()
    }

    private fun wordsForCategory(code: String) =
        categoriesRawData.firstOrNull { it.first == code }?.third ?: emptyList()

    private fun normalizeText(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .lowercase(Locale.getDefault())

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}