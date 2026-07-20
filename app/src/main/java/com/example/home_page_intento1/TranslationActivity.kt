package com.example.home_page_intento1

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranslationActivity : AppCompatActivity() {

    private enum class RecordingState { INITIAL, RECORDING, PAUSED, PREVIEW }
    private var currentState = RecordingState.INITIAL

    // ── UI – top strip ────────────────────────────────────────────
    private lateinit var btnTransBack:            ImageButton
    private lateinit var btnTransSwitchCamera:    ImageButton
    private lateinit var txtTransRecordingStatus: TextView

    // ── UI – bottom bar ───────────────────────────────────────────
    private lateinit var btnTransRecord:         ImageButton
    private lateinit var transRecordingControls: LinearLayout
    private lateinit var btnTransPauseResume:    ImageButton
    private lateinit var btnTransStop:           ImageButton
    private lateinit var transPreviewControls:   LinearLayout
    private lateinit var btnTransPreviewClose:   ImageButton
    private lateinit var btnTransProcess:        ImageButton

    // ── UI – video area ───────────────────────────────────────────
    private lateinit var previewView:           PreviewView
    private lateinit var playerViewTranslation: PlayerView
    private lateinit var btnTransPlayPause:     ImageButton

    // ── CameraX ───────────────────────────────────────────────────
    private var cameraProvider:  ProcessCameraProvider? = null
    private var videoCapture:    VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private lateinit var cameraExecutor: ExecutorService
    private var recordedFileUri: Uri? = null

    // ── ExoPlayer (preview) ───────────────────────────────────────
    private var previewExoPlayer: ExoPlayer? = null
    private val playPauseHandler = Handler(Looper.getMainLooper())
    private val HIDE_DELAY_MS = 2000L

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation)
        cameraExecutor = Executors.newSingleThreadExecutor()
        initViews()
        setupButtons()
        setupNavigation()
        if (allPermissionsGranted()) startCamera() else requestPermissions()
        setState(RecordingState.INITIAL)
    }

    override fun onResume() {
        super.onResume()
        // FIX: When returning from ProcessedVideoActivity (Back button),
        // currentState == PREVIEW but previewExoPlayer was released.
        // Re-initialize the player so the video reappears (not just audio).
        if (currentState == RecordingState.PREVIEW) {
            if (previewExoPlayer == null && recordedFileUri != null) {
                setupPreviewPlayer()
            } else {
                // Player exists: ensure it is paused (not auto-playing silently)
                previewExoPlayer?.pause()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause playback when leaving the screen (good practice)
        previewExoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        releasePreviewPlayer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        handleBackAction()
    }

    // ── Init ──────────────────────────────────────────────────────
    private fun initViews() {
        btnTransBack            = findViewById(R.id.btnTransBack)
        btnTransSwitchCamera    = findViewById(R.id.btnTransSwitchCamera)
        txtTransRecordingStatus = findViewById(R.id.txtTransRecordingStatus)
        btnTransRecord          = findViewById(R.id.btnTransRecord)
        transRecordingControls  = findViewById(R.id.transRecordingControls)
        btnTransPauseResume     = findViewById(R.id.btnTransPauseResume)
        btnTransStop            = findViewById(R.id.btnTransStop)
        transPreviewControls    = findViewById(R.id.transPreviewControls)
        btnTransPreviewClose    = findViewById(R.id.btnTransPreviewClose)
        btnTransProcess         = findViewById(R.id.btnTransProcess)
        previewView             = findViewById(R.id.previewViewTranslation)
        playerViewTranslation   = findViewById(R.id.playerViewTranslation)
        btnTransPlayPause       = findViewById(R.id.btnTransPlayPause)
    }

    // ── Buttons ───────────────────────────────────────────────────
    private fun setupButtons() {
        btnTransBack.setOnClickListener { handleBackAction() }

        btnTransSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
            startCamera()
        }

        btnTransRecord.setOnClickListener { startRecording() }

        btnTransPauseResume.setOnClickListener {
            when (currentState) {
                RecordingState.RECORDING -> pauseRecording()
                RecordingState.PAUSED    -> resumeRecording()
                else -> {}
            }
        }

        btnTransStop.setOnClickListener { stopRecording() }

        btnTransPreviewClose.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Descartar video")
                .setMessage("¿Deseas descartar el video y volver a grabar?")
                .setPositiveButton("Descartar") { _, _ -> resetToCamera() }
                .setNegativeButton("Cancelar", null).show()
        }

        btnTransProcess.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Procesar video")
                .setMessage("¿Deseas procesar este video de señas?")
                .setPositiveButton("Procesar") { _, _ -> goToProcessedVideo() }
                .setNegativeButton("Cancelar", null).show()
        }

        btnTransPlayPause.setOnClickListener {
            previewExoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            schedulePlayPauseHide()
        }
        playerViewTranslation.setOnClickListener { showPlayPauseButton() }
    }

    private fun handleBackAction() {
        when (currentState) {
            RecordingState.INITIAL -> finish()
            RecordingState.RECORDING, RecordingState.PAUSED -> {
                AlertDialog.Builder(this)
                    .setTitle("Salir")
                    .setMessage("¿Deseas detener la grabación y volver?")
                    .setPositiveButton("Volver") { _, _ ->
                        activeRecording?.stop(); activeRecording = null; finish()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            RecordingState.PREVIEW -> {
                AlertDialog.Builder(this)
                    .setTitle("Volver")
                    .setMessage("¿Deseas cerrar el video y volver a Aprendizaje?")
                    .setPositiveButton("Volver") { _, _ ->
                        releasePreviewPlayer(); finish()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    // ── CameraX ───────────────────────────────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build()
        videoCapture = VideoCapture.withOutput(recorder)
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Recording ─────────────────────────────────────────────────
    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LSC_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LSC-Talker")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()

        val recording = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) recording.withAudioEnabled()

        activeRecording = recording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start    -> setState(RecordingState.RECORDING)
                is VideoRecordEvent.Pause    -> setState(RecordingState.PAUSED)
                is VideoRecordEvent.Resume   -> setState(RecordingState.RECORDING)
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        recordedFileUri = event.outputResults.outputUri
                        setState(RecordingState.PREVIEW)
                    } else {
                        Toast.makeText(this, "Error al guardar video", Toast.LENGTH_SHORT).show()
                        setState(RecordingState.INITIAL)
                    }
                }
                else -> {}
            }
        }
    }

    private fun pauseRecording()  { activeRecording?.pause() }
    private fun resumeRecording() { activeRecording?.resume() }
    private fun stopRecording()   { activeRecording?.stop(); activeRecording = null }

    // ── State machine ─────────────────────────────────────────────
    private fun setState(state: RecordingState) {
        currentState = state
        runOnUiThread {
            // Reset all dynamic elements
            btnTransRecord.visibility          = View.GONE
            transRecordingControls.visibility  = View.GONE
            transPreviewControls.visibility    = View.GONE
            btnTransSwitchCamera.visibility    = View.GONE
            txtTransRecordingStatus.visibility = View.GONE
            previewView.visibility             = View.VISIBLE
            playerViewTranslation.visibility   = View.GONE
            btnTransPlayPause.visibility       = View.GONE

            when (state) {
                RecordingState.INITIAL -> {
                    btnTransRecord.visibility       = View.VISIBLE
                    btnTransSwitchCamera.visibility = View.VISIBLE
                }
                RecordingState.RECORDING -> {
                    transRecordingControls.visibility  = View.VISIBLE
                    txtTransRecordingStatus.text       = "● GRABANDO"
                    txtTransRecordingStatus.visibility = View.VISIBLE
                    btnTransPauseResume.setImageResource(R.drawable.icon_ic_pause_recording)
                }
                RecordingState.PAUSED -> {
                    transRecordingControls.visibility  = View.VISIBLE
                    txtTransRecordingStatus.text       = "|| PAUSADO"
                    txtTransRecordingStatus.visibility = View.VISIBLE
                    btnTransPauseResume.setImageResource(R.drawable.icon_ic_resume_recording)
                }
                RecordingState.PREVIEW -> {
                    previewView.visibility           = View.GONE
                    playerViewTranslation.visibility = View.VISIBLE
                    transPreviewControls.visibility  = View.VISIBLE
                    setupPreviewPlayer()
                }
            }
        }
    }

    // ── Preview player ────────────────────────────────────────────
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setupPreviewPlayer() {
        releasePreviewPlayer()
        val uri = recordedFileUri ?: return

        previewExoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerViewTranslation.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) {
                    if (s == Player.STATE_READY) showPlayPauseButton()
                    if (s == Player.STATE_ENDED) {
                        player.seekTo(0); player.pause(); updatePlayPauseIcon(false)
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                }
            })
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }
    }

    private fun releasePreviewPlayer() {
        playPauseHandler.removeCallbacksAndMessages(null)
        previewExoPlayer?.stop()
        previewExoPlayer?.release()
        previewExoPlayer = null
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnTransPlayPause.setImageResource(
            if (isPlaying) R.drawable.icon_ic_pause else R.drawable.icon_ic_play
        )
    }

    private fun showPlayPauseButton() {
        updatePlayPauseIcon(previewExoPlayer?.isPlaying ?: false)
        btnTransPlayPause.visibility = View.VISIBLE
        schedulePlayPauseHide()
    }

    private fun schedulePlayPauseHide() {
        playPauseHandler.removeCallbacksAndMessages(null)
        playPauseHandler.postDelayed({ btnTransPlayPause.visibility = View.GONE }, HIDE_DELAY_MS)
    }

    private fun resetToCamera() {
        releasePreviewPlayer()
        recordedFileUri = null
        setState(RecordingState.INITIAL)
        startCamera()
    }

    private fun goToProcessedVideo() {
        // FIX: Do NOT release the player before going to ProcessedVideoActivity.
        // Instead, pause it. onResume() will re-initialize if needed.
        // Releasing here was the root cause of the black screen on Back.
        previewExoPlayer?.pause()
        startActivity(Intent(this, ProcessedVideoActivity::class.java).apply {
            putExtra(ProcessedVideoActivity.EXTRA_VIDEO_URI, recordedFileUri?.toString())
        })
    }

    // ── Permissions ───────────────────────────────────────────────
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == CAMERA_PERMISSION_CODE && allPermissionsGranted()) startCamera()
        else {
            Toast.makeText(this, "Se necesitan permisos de cámara y audio.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Navigation ────────────────────────────────────────────────
    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btnTransNavHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnTransNavVideos).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnTransNavLearn).setOnClickListener {
            startActivity(Intent(this, LearnActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<ImageButton>(R.id.btnTransNavProfile).setOnClickListener {
            Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show()
        }
    }
}