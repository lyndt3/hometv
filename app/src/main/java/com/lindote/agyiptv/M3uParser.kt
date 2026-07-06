package com.lindote.agyiptv

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

object M3uParser {
    private const val TAG = "M3uParser"
    
    private const val CACHE_FILE_NAME = "cached_playlist.m3u"
    private const val PREFS_NAME = "M3uParserPrefs"
    private const val KEY_LAST_DOWNLOAD = "last_download_time"
    private const val CACHE_DURATION_MS = 7 * 24 * 60 * 60 * 1000L // 7 dias

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun fetchAndParse(context: Context, forceRefresh: Boolean = false, callback: (List<Category>?, List<LiveStream>?, String?) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDownload = prefs.getLong(KEY_LAST_DOWNLOAD, 0L)
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        val now = System.currentTimeMillis()

        // Se não for forçado, a cache local existe e tem menos de 7 dias, carrega de imediato
        if (!forceRefresh && cacheFile.exists() && (now - lastDownload) < CACHE_DURATION_MS) {
            Log.i(TAG, "Cache local válida. A abrir stream de leitura...")
            Thread {
                try {
                    val reader = cacheFile.bufferedReader()
                    parseContent(reader, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao abrir ficheiro de cache, a forçar download: ${e.message}")
                    fetchFromNetwork(context, cacheFile, prefs, now, callback)
                }
            }.start()
            return
        }

        // Caso forceRefresh seja true, ou a cache não exista/exceda o tempo, descarrega online
        fetchFromNetwork(context, cacheFile, prefs, now, callback)
    }

    private fun fetchFromNetwork(
        context: Context,
        cacheFile: File,
        prefs: android.content.SharedPreferences,
        now: Long,
        callback: (List<Category>?, List<LiveStream>?, String?) -> Unit
    ) {
        Log.i(TAG, "A descarregar nova lista M3U da Internet...")
        val username = prefs.getString("username", "FerreiraLindote") ?: "FerreiraLindote"
        val password = prefs.getString("password", "123digalaoutravez1") ?: "123digalaoutravez1"
        val url = "http://x96.us:8880/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Falha de rede ao obter M3U: ${e.message}. A usar cache antiga se disponível", e)
                fallbackToCacheOrAsset(context, cacheFile, callback)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    Thread {
                        try {
                            // Grava em cache local
                            cacheFile.writeText(body)
                            // Atualiza timestamp da última transferência
                            prefs.edit().putLong(KEY_LAST_DOWNLOAD, now).apply()
                            
                            val reader = BufferedReader(StringReader(body))
                            parseContent(reader, callback)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao guardar ou analisar lista descarregada: ${e.message}", e)
                            fallbackToCacheOrAsset(context, cacheFile, callback)
                        }
                    }.start()
                } else {
                    Log.e(TAG, "Código de resposta HTTP falhou: ${response.code}")
                    fallbackToCacheOrAsset(context, cacheFile, callback)
                }
            }
        })
    }

    private fun fallbackToCacheOrAsset(
        context: Context,
        cacheFile: File,
        callback: (List<Category>?, List<LiveStream>?, String?) -> Unit
    ) {
        Thread {
            if (cacheFile.exists()) {
                Log.i(TAG, "A usar cópia local de segurança (cache expirada)...")
                try {
                    val reader = cacheFile.bufferedReader()
                    parseContent(reader, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha crítica ao ler cache antiga: ${e.message}")
                    loadLocal(context, callback)
                }
            } else {
                loadLocal(context, callback)
            }
        }.start()
    }

    fun loadLocal(context: Context, callback: (List<Category>?, List<LiveStream>?, String?) -> Unit) {
        Thread {
            Log.i(TAG, "A carregar playlist fallback local do assets...")
            try {
                val assetManager = context.assets
                val inputStream = assetManager.open("playlist.m3u")
                val reader = BufferedReader(InputStreamReader(inputStream))
                parseContent(reader, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao ler ficheiro local do assets: ${e.message}", e)
                callback(null, null, e.message)
            }
        }.start()
    }

    private fun parseContent(reader: BufferedReader, callback: (List<Category>?, List<LiveStream>?, String?) -> Unit) {
        val categories = mutableListOf<Category>()
        val streams = mutableListOf<LiveStream>()
        val catNames = mutableSetOf<String>()

        try {
            var line = reader.readLine()
            var currentTvgId = ""
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Outros"
            var streamCount = 1

            while (line != null) {
                // Evita chamar trim() desnecessariamente em todas as 110.000 linhas
                if (line.startsWith("#EXTINF:")) {
                    currentLogo = parseAttribute(line, "tvg-logo")
                    currentTvgId = parseAttribute(line, "tvg-id")
                    val groupAttr = parseAttribute(line, "group-title")
                    
                    val commaIndex = line.lastIndexOf(',')
                    currentName = if (commaIndex != -1) {
                        line.substring(commaIndex + 1).trim()
                    } else {
                        "Canal $streamCount"
                    }

                    currentGroup = if (groupAttr.isNotEmpty()) {
                        groupAttr
                    } else {
                        if (currentName.startsWith("PT:") || currentName.contains("PT -") || currentName.startsWith("PT :")) {
                            "Portugal"
                        } else if (currentName.startsWith("ES:") || currentName.contains("ES -")) {
                            "Espanha"
                        } else if (currentName.contains("⭐⭐⭐") || currentName.contains("---")) {
                            "Delimiter"
                        } else {
                            val colonIndex = currentName.indexOf(':')
                            if (colonIndex != -1 && colonIndex < 10) {
                                currentName.substring(0, colonIndex).trim()
                            } else {
                                "Outros"
                            }
                        }
                    }
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    if (currentGroup != "Delimiter" && !currentName.contains("⭐⭐⭐") && !currentName.contains("---")) {
                        if (currentGroup !in catNames) {
                            catNames.add(currentGroup)
                            categories.add(Category(currentGroup, currentGroup))
                        }
                        val trimmedUrl = line.trim()
                        val realStreamId = try {
                            val lastPart = trimmedUrl.substringAfterLast('/')
                            val cleanId = lastPart.substringBefore('.')
                            cleanId.toInt()
                        } catch (e: Exception) {
                            streamCount
                        }
                        
                        streams.add(
                            LiveStream(
                                num = streamCount++,
                                name = currentName,
                                streamId = realStreamId,
                                streamIcon = currentLogo.ifEmpty { null },
                                categoryId = currentGroup,
                                url = trimmedUrl,
                                tvgId = currentTvgId.ifEmpty { null }
                            )
                        )
                    }
                    currentName = ""
                    currentLogo = ""
                    currentTvgId = ""
                    currentGroup = "Outros"
                }
                line = reader.readLine()
            }
            reader.close()
            callback(categories, streams, null)
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante a leitura e análise: ${e.message}", e)
            callback(null, null, e.message)
        }
    }

    private fun parseAttribute(line: String, attributeName: String): String {
        val key = "$attributeName=\""
        val start = line.indexOf(key)
        if (start == -1) return ""
        val startIndex = start + key.length
        val endIndex = line.indexOf("\"", startIndex)
        if (endIndex == -1) return ""
        return line.substring(startIndex, endIndex)
    }
}
