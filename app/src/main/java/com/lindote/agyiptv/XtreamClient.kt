package com.lindote.agyiptv

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class EpgProgram(
    val title: String,          // Base64 codificado na resposta original
    val description: String?,   // Base64 codificado na resposta original
    val start: String,          // "yyyy-MM-dd HH:mm:ss"
    val end: String,            // "yyyy-MM-dd HH:mm:ss"
    val start_timestamp: Long,
    val stop_timestamp: Long
)

data class EpgResponse(
    val epg_listings: List<EpgProgram>?
)

object XtreamClient {
    private const val HOST = "http://x96.us:8880"
    private const val USERNAME = "FerreiraLindote"
    private const val PASSWORD = "123digalaoutravez1"

    private val client = OkHttpClient()
    private val gson = Gson()

    fun getCategories(callback: (List<Category>?, String?) -> Unit) {
        val url = "$HOST/player_api.php?username=$USERNAME&password=$PASSWORD&action=get_live_categories"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val listType = object : TypeToken<List<Category>>() {}.type
                        val categories: List<Category> = gson.fromJson(body, listType)
                        callback(categories, null)
                    } catch (e: Exception) {
                        callback(null, e.message)
                    }
                } else {
                    callback(null, "Erro na resposta: ${response.code}")
                }
            }
        })
    }

    fun getLiveStreams(callback: (List<LiveStream>?, String?) -> Unit) {
        val url = "$HOST/player_api.php?username=$USERNAME&password=$PASSWORD&action=get_live_streams"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val listType = object : TypeToken<List<LiveStream>>() {}.type
                        val streams: List<LiveStream> = gson.fromJson(body, listType)
                        callback(streams, null)
                    } catch (e: Exception) {
                        callback(null, e.message)
                    }
                } else {
                    callback(null, "Erro na resposta: ${response.code}")
                }
            }
        })
    }

    fun getShortEpg(context: android.content.Context, streamId: Int, callback: (List<EpgProgram>?, String?) -> Unit) {
        val prefs = context.getSharedPreferences("M3uParserPrefs", android.content.Context.MODE_PRIVATE)
        val username = prefs.getString("username", "FerreiraLindote") ?: "FerreiraLindote"
        val password = prefs.getString("password", "123digalaoutravez1") ?: "123digalaoutravez1"
        val url = "$HOST/player_api.php?username=$username&password=$password&action=get_short_epg&stream_id=$streamId"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val epgResponse = gson.fromJson(body, EpgResponse::class.java)
                        val decodedList = epgResponse.epg_listings?.map { prog ->
                            val decodedTitle = try {
                                String(android.util.Base64.decode(prog.title, android.util.Base64.DEFAULT), Charsets.UTF_8)
                            } catch (e: Exception) {
                                prog.title
                            }
                            val decodedDesc = try {
                                prog.description?.let {
                                    String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                }
                            } catch (e: Exception) {
                                prog.description
                            }
                            prog.copy(title = decodedTitle, description = decodedDesc)
                        }
                        callback(decodedList, null)
                    } catch (e: Exception) {
                        callback(null, e.message)
                    }
                } else {
                    callback(null, "HTTP ${response.code}")
                }
            }
        })
    }

    fun getStreamUrl(streamId: Int): String {
        return "$HOST/live/$USERNAME/$PASSWORD/$streamId.ts"
    }
}
