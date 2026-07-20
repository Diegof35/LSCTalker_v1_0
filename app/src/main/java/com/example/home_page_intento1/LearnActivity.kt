package com.example.home_page_intento1

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LearnActivity : AppCompatActivity() {

    private lateinit var edtLearnSearch:            EditText
    private lateinit var imgLearnSearchIcon:        ImageView
    private lateinit var cardAprendizaje:           ImageView
    private lateinit var cardTraduccion:            ImageView
    private lateinit var txtLearnAprendizajeArrow:  TextView
    private lateinit var txtLearnTraduccionArrow:   TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn)
        initViews()
        setupClickListeners()
        setupNavigation()
    }

    private fun initViews() {
        edtLearnSearch           = findViewById(R.id.edtLearnSearch)
        imgLearnSearchIcon       = findViewById(R.id.imgLearnSearchIcon)
        cardAprendizaje          = findViewById(R.id.cardAprendizaje)
        cardTraduccion           = findViewById(R.id.cardTraduccion)
        txtLearnAprendizajeArrow = findViewById(R.id.txtLearnAprendizajeArrow)
        txtLearnTraduccionArrow  = findViewById(R.id.txtLearnTraduccionArrow)
    }

    private fun setupClickListeners() {

        val goSearch: (String) -> Unit = { query ->
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(edtLearnSearch.windowToken, 0)
            val intent = Intent(this, SearchActivity::class.java)
            if (query.isNotBlank()) intent.putExtra(SearchActivity.EXTRA_INITIAL_QUERY, query)
            startActivity(intent)
        }

        // FIX 2.1: imgLearnSearchIcon click triggers search (same behaviour as imgSearchIcon in other screens)
        imgLearnSearchIcon.setOnClickListener { goSearch(edtLearnSearch.text.toString().trim()) }

        // Enter key also triggers search
        edtLearnSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                goSearch(v.text.toString().trim()); true
            } else false
        }

        // Aprendizaje → SearchActivity with Temas expanded
        val goLearn = { startActivity(Intent(this, SearchActivity::class.java).apply { putExtra(SearchActivity.EXTRA_LEARN_MODE, true) }) }
        cardAprendizaje.setOnClickListener          { goLearn() }
        txtLearnAprendizajeArrow.setOnClickListener { goLearn() }

        // Traducción → TranslationActivity
        val goTranslate = { startActivity(Intent(this, TranslationActivity::class.java)) }
        cardTraduccion.setOnClickListener          { goTranslate() }
        txtLearnTraduccionArrow.setOnClickListener { goTranslate() }
    }

    private fun setupNavigation() {
        findViewById<ImageButton>(R.id.btnLearnNavHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<ImageButton>(R.id.btnLearnNavVideos).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        }
        findViewById<ImageButton>(R.id.btnLearnNavLearn).setOnClickListener { }
        findViewById<ImageButton>(R.id.btnLearnNavProfile).setOnClickListener {
            Toast.makeText(this, "Perfil – próximamente", Toast.LENGTH_SHORT).show()
        }
    }
}