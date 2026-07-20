package com.example.home_page_intento1

import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DriveVideoRepository {

    private const val TAG = "DriveVideoRepo"

    private const val ROOT_FOLDER_ID = "1zT7NUI7yEPQJccUuHQ2XSMuKIYjnpuEl"

    // TODO: reemplaza este valor con tu API Key real de Google Cloud Console
    var API_KEY: String = "AIzaSyAIt7pXdMglKUr7Vgc0NLJDOrX0OK-bm6c"

    private const val API_BASE = "https://www.googleapis.com/drive/v3"

    private val subfolderIds        = mutableMapOf<String, String>()
    private val fileIdsByCategory   = mutableMapOf<String, List<String>>()
    @Volatile private var subfoldersFetched = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Punto de entrada público ──────────────────────────────────────────
    fun getVideoUrl(categoryCode: String, wordIndex: Int, callback: (String?) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "▶ getVideoUrl(category=$categoryCode, index=$wordIndex)")

                // Paso 1: obtener subcarpetas (solo una vez)
                if (!subfoldersFetched) {
                    Log.d(TAG, "Paso 1: consultando subcarpetas de la carpeta raíz...")
                    fetchSubfoldersSync()
                    Log.d(TAG, "Paso 1 OK: ${subfolderIds.size} subcarpetas encontradas → $subfolderIds")
                } else {
                    Log.d(TAG, "Paso 1: subcarpetas ya en caché → $subfolderIds")
                }

                // Paso 2: obtener archivos de la categoría (solo una vez por categoría)
                if (!fileIdsByCategory.containsKey(categoryCode)) {
                    val folderId = subfolderIds[categoryCode]
                    if (folderId == null) {
                        Log.e(TAG, "ERROR Paso 2: no se encontró subcarpeta para código '$categoryCode'")
                        mainHandler.post { callback(null) }
                        return@Thread
                    }
                    Log.d(TAG, "Paso 2: consultando archivos de categoría '$categoryCode' (folderId=$folderId)...")
                    fetchFilesSync(categoryCode, folderId)
                    Log.d(TAG, "Paso 2 OK: ${fileIdsByCategory[categoryCode]?.size} archivos encontrados")
                } else {
                    Log.d(TAG, "Paso 2: archivos de '$categoryCode' ya en caché (${fileIdsByCategory[categoryCode]?.size} items)")
                }

                // Paso 3: construir URL
                val ids = fileIdsByCategory[categoryCode]
                if (ids == null || wordIndex !in ids.indices) {
                    Log.e(TAG, "ERROR Paso 3: índice $wordIndex fuera de rango (total=${ids?.size})")
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                val fileId = ids[wordIndex]
                val url    = "$API_BASE/files/$fileId?alt=media&key=$API_KEY"
                Log.d(TAG, "Paso 3 OK: URL construida → $url")

                mainHandler.post { callback(url) }

            } catch (e: Exception) {
                Log.e(TAG, "EXCEPCIÓN en getVideoUrl: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                mainHandler.post { callback(null) }
            }
        }.start()
    }

    // ── Helpers privados ──────────────────────────────────────────────────

    private fun fetchSubfoldersSync() {
        val query = "'$ROOT_FOLDER_ID' in parents " +
                "and mimeType='application/vnd.google-apps.folder' " +
                "and trashed=false"
        val url   = buildListUrl(query, fields = "files(id,name)", pageSize = 50)
        Log.d(TAG, "fetchSubfoldersSync URL: $url")

        val json  = httpGet(url)
        Log.d(TAG, "fetchSubfoldersSync respuesta: $json")

        val files = JSONObject(json).getJSONArray("files")
        for (i in 0 until files.length()) {
            val obj  = files.getJSONObject(i)
            val name = obj.getString("name")
            val id   = obj.getString("id")
            if (name.length >= 2) subfolderIds[name.take(2)] = id
        }
        subfoldersFetched = true
    }

    private fun fetchFilesSync(categoryCode: String, folderId: String) {
        val query = "'$folderId' in parents and trashed=false"
        val url   = buildListUrl(query, fields = "files(id,name)", pageSize = 200)
        Log.d(TAG, "fetchFilesSync URL: $url")

        val json  = httpGet(url)
        Log.d(TAG, "fetchFilesSync respuesta: $json")

        val files = JSONObject(json).getJSONArray("files")

        data class Entry(val sortKey: String, val fileId: String)
        val entries = mutableListOf<Entry>()

        for (i in 0 until files.length()) {
            val obj     = files.getJSONObject(i)
            val name    = obj.getString("name")
            val id      = obj.getString("id")
            val sortKey = name.substringBeforeLast('.').uppercase()
            entries.add(Entry(sortKey, id))
        }

        entries.sortBy { it.sortKey }
        fileIdsByCategory[categoryCode] = entries.map { it.fileId }

        Log.d(TAG, "fetchFilesSync: archivos ordenados para '$categoryCode':")
        entries.forEachIndexed { i, e -> Log.d(TAG, "  [$i] ${e.sortKey}") }
    }

    private fun buildListUrl(query: String, fields: String, pageSize: Int): String {
        val encodedQuery  = URLEncoder.encode(query,  "UTF-8")
        val encodedFields = URLEncoder.encode(fields, "UTF-8")
        return "$API_BASE/files" +
                "?q=$encodedQuery" +
                "&fields=$encodedFields" +
                "&pageSize=$pageSize" +
                "&key=$API_KEY"
    }

    /**
     * HTTP GET síncrono.
     * Lee el errorStream cuando el código de respuesta no es 2xx,
     * para que el mensaje de error de la API sea visible en los logs.
     */
    @Throws(Exception::class)
    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout    = 15_000

        return try {
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code para: $urlString")

            if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                // Leer el cuerpo del error para diagnosticar 403, 400, etc.
                val errorBody = conn.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    ?: "(sin cuerpo de error)"
                Log.e(TAG, "HTTP $code error body: $errorBody")
                throw Exception("HTTP $code: $errorBody")
            }
        } finally {
            conn.disconnect()
        }
    }
}