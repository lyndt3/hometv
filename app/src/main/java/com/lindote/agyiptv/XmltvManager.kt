package com.lindote.agyiptv

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object XmltvManager {
    private const val TAG = "XmltvManager"
    private const val CACHE_FILE_NAME = "cached_epg.xml"
    private const val PREFS_NAME = "M3uParserPrefs"
    private const val KEY_LAST_EPG_DOWNLOAD = "last_epg_download_time"
    private const val EPG_CACHE_DURATION_MS = 12 * 60 * 60 * 1000L // 12 horas

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val epgMap = mutableMapOf<String, MutableList<EpgProgram>>()
    private var isLoaded = false

    fun getProgramsForChannel(tvgId: String?): List<EpgProgram> {
        if (tvgId == null) return emptyList()
        return epgMap[tvgId] ?: emptyList()
    }

    fun prepareEpg(context: Context, activeTvgIds: Set<String>, callback: (Boolean) -> Unit) {
        if (isLoaded) {
            callback(true)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDownload = prefs.getLong(KEY_LAST_EPG_DOWNLOAD, 0L)
        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        val now = System.currentTimeMillis()

        Thread {
            try {
                if (cacheFile.exists() && (now - lastDownload) < EPG_CACHE_DURATION_MS) {
                    Log.i(TAG, "Cache do XMLTV válida. A ler do disco...")
                    parseXmltv(cacheFile, activeTvgIds)
                    isLoaded = true
                    callback(true)
                } else {
                    Log.i(TAG, "A descarregar novo guia XMLTV...")
                    val username = prefs.getString("username", "FerreiraLindote") ?: "FerreiraLindote"
                    val password = prefs.getString("password", "123digalaoutravez1") ?: "123digalaoutravez1"
                    val url = "http://x96.us:8880/xmltv.php?username=$username&password=$password"
                    
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val bodyStream = response.body?.byteStream()
                        if (bodyStream != null) {
                            val fos = FileOutputStream(cacheFile)
                            val buffer = ByteArray(1024 * 8)
                            var bytesRead = bodyStream.read(buffer)
                            while (bytesRead != -1) {
                                fos.write(buffer, 0, bytesRead)
                                bytesRead = bodyStream.read(buffer)
                            }
                            fos.flush()
                            fos.close()
                            bodyStream.close()
                            
                            prefs.edit().putLong(KEY_LAST_EPG_DOWNLOAD, now).apply()
                            
                            parseXmltv(cacheFile, activeTvgIds)
                            isLoaded = true
                            callback(true)
                        } else {
                            callback(false)
                        }
                    } else {
                        Log.e(TAG, "Erro de rede ao baixar XMLTV: ${response.code}")
                        if (cacheFile.exists()) {
                            Log.i(TAG, "Erro de rede, usando cache antiga...")
                            parseXmltv(cacheFile, activeTvgIds)
                            isLoaded = true
                            callback(true)
                        } else {
                            callback(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar XMLTV: ${e.message}", e)
                if (cacheFile.exists()) {
                    try {
                        parseXmltv(cacheFile, activeTvgIds)
                        isLoaded = true
                        callback(true)
                    } catch (ex: Exception) {
                        callback(false)
                    }
                } else {
                    callback(false)
                }
            }
        }.start()
    }

    private fun parseXmltv(file: File, activeTvgIds: Set<String>) {
        epgMap.clear()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        val fis = FileInputStream(file)
        parser.setInput(fis, "UTF-8")

        var eventType = parser.eventType
        var currentTvgId = ""
        var startAttr = ""
        var endAttr = ""
        var titleText = ""
        var descText = ""
        var tempText = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName == "programme") {
                        currentTvgId = parser.getAttributeValue(null, "channel") ?: ""
                        startAttr = parser.getAttributeValue(null, "start") ?: ""
                        endAttr = parser.getAttributeValue(null, "stop") ?: ""
                        titleText = ""
                        descText = ""
                    }
                    tempText = ""
                }
                XmlPullParser.TEXT -> {
                    tempText = parser.text ?: ""
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "title") {
                        titleText = tempText
                    } else if (tagName == "desc") {
                        descText = tempText
                    } else if (tagName == "programme") {
                        if (currentTvgId.isNotEmpty() && activeTvgIds.contains(currentTvgId)) {
                            val formattedStart = convertXmltvTime(startAttr)
                            val formattedEnd = convertXmltvTime(endAttr)
                            
                            val program = EpgProgram(
                                title = titleText.ifEmpty { "Sem título" },
                                description = descText,
                                start = formattedStart,
                                end = formattedEnd,
                                start_timestamp = 0L,
                                stop_timestamp = 0L
                            )
                            
                            val list = epgMap.getOrPut(currentTvgId) { mutableListOf() }
                            list.add(program)
                        }
                        currentTvgId = ""
                    }
                }
            }
            eventType = parser.next()
        }
        fis.close()
        Log.i(TAG, "XMLTV EPG analisado: ${epgMap.size} canais mapeados com sucesso!")
    }

    private fun convertXmltvTime(xmltvTime: String): String {
        try {
            val cleanTime = xmltvTime.substringBefore(' ')
            val tzPart = if (xmltvTime.contains(' ')) xmltvTime.substringAfter(' ') else "+0000"

            if (cleanTime.length >= 14) {
                val parserSdf = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
                val date = parserSdf.parse(cleanTime + " " + tzPart)
                if (date != null) {
                    val localSdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    return localSdf.format(date)
                }
            }

            if (cleanTime.length >= 14) {
                val year = cleanTime.substring(0, 4)
                val month = cleanTime.substring(4, 6)
                val day = cleanTime.substring(6, 8)
                val hour = cleanTime.substring(8, 10)
                val min = cleanTime.substring(10, 12)
                val sec = cleanTime.substring(12, 14)
                return "$year-$month-$day $hour:$min:$sec"
            }
            return xmltvTime
        } catch (e: Exception) {
            return xmltvTime
        }
    }

    fun getCurrentProgram(tvgId: String?): EpgProgram? {
        if (tvgId.isNullOrEmpty()) return null
        val listings = getProgramsForChannel(tvgId)
        if (listings.isEmpty()) return null
        
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val nowStr = sdf.format(java.util.Date())
        
        return listings.find { nowStr >= it.start && nowStr < it.end }
    }

    fun clearCache() {
        isLoaded = false
        epgMap.clear()
    }
}
