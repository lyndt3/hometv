package com.lindote.agyiptv

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer

private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var splashOverlay: View
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var menuOverlay: View
    private lateinit var playerView: VLCVideoLayout
    private lateinit var playerProgress: ProgressBar

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private lateinit var btnSettings: Button
    private lateinit var epgBanner: View
    private lateinit var ivEpgChannelLogo: ImageView
    private lateinit var tvEpgChannelName: TextView
    private lateinit var tvEpgNowTitle: TextView
    private lateinit var tvEpgNextTitle: TextView

    private val epgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideEpgRunnable = Runnable {
        epgBanner.visibility = View.GONE
    }

    private var allStreams: List<LiveStream> = emptyList()
    private var currentlySelectedStreams: List<LiveStream> = emptyList()
    private var currentPlayingIndex: Int = -1
    
    private var libVlc: LibVLC? = null
    private var mPlayer: MediaPlayer? = null

    private var allCategories: List<Category> = emptyList()

    private val playbackCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPlaybackPosition: Long = -1
    private var lastPositionCheckTime: Long = 0
    private var bufferingStartTime: Long = 0

    private val checkPlaybackRunnable = object : Runnable {
        override fun run() {
            val p = mPlayer
            if (p != null && p.isPlaying) {
                val currentPos = p.time
                val now = System.currentTimeMillis()

                if (currentPos == lastPlaybackPosition) {
                    if (lastPositionCheckTime != 0L && now - lastPositionCheckTime > 8000L) { // Stuck/frozen for more than 8s
                        Log.w("PlayerStallDetector", "Playback position stuck. Reloading channel...")
                        lastPositionCheckTime = now
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Sinal congelado. A recarregar...", Toast.LENGTH_SHORT).show()
                            reloadCurrentChannel()
                        }
                    }
                } else {
                    lastPlaybackPosition = currentPos
                    lastPositionCheckTime = now
                }
            } else {
                lastPlaybackPosition = -1
                lastPositionCheckTime = 0L
            }
            playbackCheckHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)
        splashOverlay = findViewById(R.id.splash_overlay)
        rvCategories = findViewById(R.id.rv_categories)
        rvChannels = findViewById(R.id.rv_channels)
        menuOverlay = findViewById(R.id.menu_overlay)
        playerView = findViewById(R.id.player_view)
        playerProgress = findViewById(R.id.player_progress)

        epgBanner = findViewById(R.id.epg_banner)
        ivEpgChannelLogo = findViewById(R.id.iv_epg_channel_logo)
        tvEpgChannelName = findViewById(R.id.tv_epg_channel_name)
        tvEpgNowTitle = findViewById(R.id.tv_epg_now_title)
        tvEpgNextTitle = findViewById(R.id.tv_epg_next_title)

        btnSettings = findViewById(R.id.btn_settings)
        btnSettings.setOnClickListener {
            showCredentialsDialog()
        }

        // Setup layouts
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = GridLayoutManager(this, 2)

        // Setup adapters
        categoryAdapter = CategoryAdapter(emptyList()) { category ->
            filterChannels(category.id)
        }
        rvCategories.adapter = categoryAdapter

        channelAdapter = ChannelAdapter(emptyList()) { stream ->
            val index = currentlySelectedStreams.indexOf(stream)
            if (index != -1) {
                playChannel(index)
                hideMenu()
            }
        }
        rvChannels.adapter = channelAdapter

        // Initial setup
        initializePlayer()
        loadData()
    }

    private fun loadData() {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "A carregar lista M3U online..."

        M3uParser.fetchAndParse(this) { categories, streams, error ->
            if (error != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    splashOverlay.visibility = View.GONE
                    tvStatus.text = "Erro: $error"
                }
                return@fetchAndParse
            }

            if (categories != null && streams != null) {
                // Pre-calcula os índices de ordenação para evitar CPU overhead de 1.7M operações (30x mais rápido)
                val streamsWithSortIndex = streams.map { Pair(it, getSortIndex(it.name)) }
                val sortedStreams = streamsWithSortIndex.sortedWith(
                    compareBy<Pair<LiveStream, Int>> { it.second }
                        .thenBy { it.first.name }
                ).map { it.first }

                // Procura o canal padrão no background thread
                val defaultMatch = findDefaultChannelIndex(sortedStreams)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    splashOverlay.animate()
                        .alpha(0f)
                        .setDuration(800)
                        .withEndAction { splashOverlay.visibility = View.GONE }
                        .start()
                    allStreams = sortedStreams
                    tvStatus.text = "${categories.size} Categorias, ${sortedStreams.size} Canais"

                    allCategories = categories
                    categoryAdapter.updateData(categories)

                    // Inicializa o XMLTV EPG em background com os tvgIds dos nossos canais
                    val activeTvgIds = sortedStreams.mapNotNull { it.tvgId }.toSet()
                    XmltvManager.prepareEpg(this@MainActivity, activeTvgIds) { success ->
                        // XMLTV EPG carregado com sucesso
                    }
                    
                    if (categories.isNotEmpty()) {
                        if (defaultMatch != null) {
                            val categoryId = defaultMatch.first
                            val channelIndex = defaultMatch.second
                            filterChannels(categoryId)
                            playChannel(channelIndex)
                        } else {
                            filterChannels(categories[0].id)
                            if (currentlySelectedStreams.isNotEmpty()) {
                                playChannel(0)
                            }
                        }
                    }
                }
            } else {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progressBar.visibility = View.GONE
                    splashOverlay.visibility = View.GONE
                    tvStatus.text = "Sem dados disponiveis"
                }
            }
        }
    }

    private fun findDefaultChannelIndex(streams: List<LiveStream>): Pair<String, Int>? {
        // Tenta encontrar correspondência para "sport tv 1 hd" ou "sport tv 1 fhd"
        var foundStream = streams.find { stream ->
            val clean = stream.name.lowercase().replace(" ", "")
            clean.contains("sporttv1hd") || clean.contains("sporttv1fhd")
        }

        // Fallback: contém "sport tv 1"
        if (foundStream == null) {
            foundStream = streams.find { stream ->
                val clean = stream.name.lowercase()
                clean.contains("sport tv 1") || clean.contains("sporttv 1") || clean.contains("sporttv1")
            }
        }

        if (foundStream != null) {
            val categoryId = foundStream.categoryId
            val categoryStreams = streams.filter { it.categoryId == categoryId }
            val index = categoryStreams.indexOf(foundStream)
            if (index != -1) {
                return Pair(categoryId, index)
            }
        }
        return null
    }

    private fun filterChannels(categoryId: String, resetScroll: Boolean = true) {
        currentlySelectedStreams = allStreams.filter { it.categoryId == categoryId }
        channelAdapter.updateData(currentlySelectedStreams)
        if (resetScroll) {
            rvChannels.scrollToPosition(0)
        }
    }

    private fun initializePlayer() {
        if (libVlc == null) {
            val options = arrayListOf<String>(
                "--network-caching=1500", // Buffer of 1.5s
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",    // Drop late video frames to keep in sync
                "--skip-frames"          // Skip frames if CPU is slow
            )
            libVlc = LibVLC(this, options)
        }
        if (mPlayer == null) {
            mPlayer = MediaPlayer(libVlc).apply {
                attachViews(playerView, null, true, false)
                
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Buffering -> {
                            val bufferingProgress = event.buffering
                            val currentlyBuffering = bufferingProgress < 100f
                            playerProgress.visibility = if (currentlyBuffering) View.VISIBLE else View.GONE
                            
                            if (currentlyBuffering) {
                                if (bufferingStartTime == 0L) {
                                    bufferingStartTime = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - bufferingStartTime > 12000L) {
                                    Log.w("PlayerStallDetector", "LibVLC buffering too long. Reloading...")
                                    bufferingStartTime = 0L
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "Sinal interrompido. A recarregar...", Toast.LENGTH_SHORT).show()
                                        reloadCurrentChannel()
                                    }
                                }
                            } else {
                                bufferingStartTime = 0L
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            playerProgress.visibility = View.GONE
                            bufferingStartTime = 0L
                            lastPlaybackPosition = -1
                            lastPositionCheckTime = System.currentTimeMillis()
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            playerProgress.visibility = View.GONE
                            Log.e("PlayerError", "LibVLC encountered playback error")
                            Toast.makeText(this@MainActivity, "Erro de ligação. A tentar restabelecer...", Toast.LENGTH_SHORT).show()
                            playbackCheckHandler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    reloadCurrentChannel()
                                }
                            }, 2000L)
                        }
                    }
                }
            }
        }
    }

    private fun formatProgramTime(start: String, end: String): String {
        try {
            val startTime = start.substringAfter(' ').substringBeforeLast(':')
            val endTime = end.substringAfter(' ').substringBeforeLast(':')
            return "[$startTime - $endTime]"
        } catch (e: Exception) {
            return ""
        }
    }

    private fun playChannel(index: Int) {
        if (currentlySelectedStreams.isEmpty()) return

        var safeIndex = index
        if (safeIndex >= currentlySelectedStreams.size) {
            safeIndex = 0
        } else if (safeIndex < 0) {
            safeIndex = currentlySelectedStreams.size - 1
        }

        // Reset stall detection timers
        bufferingStartTime = 0L
        lastPlaybackPosition = -1
        lastPositionCheckTime = System.currentTimeMillis()

        currentPlayingIndex = safeIndex
        val stream = currentlySelectedStreams[safeIndex]
        val url = stream.url ?: XtreamClient.getStreamUrl(stream.streamId)

        initializePlayer()

        mPlayer?.let { player ->
            try {
                val media = Media(libVlc, android.net.Uri.parse(url)).apply {
                    addOption(":network-caching=1500")
                }
                player.media = media
                media.release()
                player.play()
            } catch (e: Exception) {
                Log.e("Player", "Error loading media in LibVLC: ${e.message}", e)
            }
        }

        // Exibe o painel de informação EPG do canal
        epgHandler.removeCallbacks(hideEpgRunnable)
        tvEpgChannelName.text = stream.name

        // Carrega o logótipo do canal usando Glide
        if (!stream.streamIcon.isNullOrEmpty()) {
            ivEpgChannelLogo.visibility = View.VISIBLE
            Glide.with(this)
                .load(stream.streamIcon)
                .placeholder(R.drawable.app_banner)
                .error(R.drawable.app_banner)
                .into(ivEpgChannelLogo)
        } else {
            ivEpgChannelLogo.visibility = View.GONE
        }

        tvEpgNowTitle.text = "A carregar..."
        tvEpgNextTitle.text = "A carregar..."

        // Atualiza o relógio da EPG com a data e hora local
        val clockSdf = java.text.SimpleDateFormat("dd/MM - HH:mm", java.util.Locale.getDefault())
        findViewById<TextView>(R.id.tv_epg_clock).text = clockSdf.format(java.util.Date())

        epgBanner.visibility = View.VISIBLE
        epgHandler.postDelayed(hideEpgRunnable, 5000L) // Desaparece após 5 segundos

        // Procura EPG primeiro no XMLTVManager local
        val localListings = XmltvManager.getProgramsForChannel(stream.tvgId)
        val activeListings = filterCurrentAndNextPrograms(localListings)

        if (activeListings.isNotEmpty()) {
            val nowProgram = activeListings[0]
            val nowTime = formatProgramTime(nowProgram.start, nowProgram.end)
            tvEpgNowTitle.text = "$nowTime ${nowProgram.title}"

            if (activeListings.size > 1) {
                val nextProgram = activeListings[1]
                val nextTime = formatProgramTime(nextProgram.start, nextProgram.end)
                tvEpgNextTitle.text = "$nextTime ${nextProgram.title}"
            } else {
                tvEpgNextTitle.text = "Sem informação de programa seguinte"
            }
        } else {
            // Caso não tenha no XMLTV local, faz o fallback para o Short EPG da API
            XtreamClient.getShortEpg(this, stream.streamId) { listings, _ ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (listings != null && listings.isNotEmpty()) {
                        val nowProgram = listings[0]
                        val nowTime = formatProgramTime(nowProgram.start, nowProgram.end)
                        tvEpgNowTitle.text = "$nowTime ${nowProgram.title}"

                        if (listings.size > 1) {
                            val nextProgram = listings[1]
                            val nextTime = formatProgramTime(nextProgram.start, nextProgram.end)
                            tvEpgNextTitle.text = "$nextTime ${nextProgram.title}"
                        } else {
                            tvEpgNextTitle.text = "Sem informação de programa seguinte"
                        }
                    } else {
                        tvEpgNowTitle.text = "Sem informação de programa"
                        tvEpgNextTitle.text = "Sem informação de programa seguinte"
                    }
                }
            }
        }
    }

    private fun getCurrentDateTimeString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun filterCurrentAndNextPrograms(listings: List<EpgProgram>): List<EpgProgram> {
        if (listings.isEmpty()) return emptyList()
        val nowStr = getCurrentDateTimeString()
        val sorted = listings.sortedBy { it.start }
        val currentIndex = sorted.indexOfFirst { nowStr >= it.start && nowStr < it.end }
        if (currentIndex != -1) {
            val result = mutableListOf<EpgProgram>()
            result.add(sorted[currentIndex])
            if (currentIndex + 1 < sorted.size) {
                result.add(sorted[currentIndex + 1])
            }
            return result
        }
        val nextIndex = sorted.indexOfFirst { it.start > nowStr }
        if (nextIndex != -1) {
            val result = mutableListOf<EpgProgram>()
            result.add(sorted[nextIndex])
            if (nextIndex + 1 < sorted.size) {
                result.add(sorted[nextIndex + 1])
            }
            return result
        }
        return emptyList()
    }

    private fun showCredentialsDialog() {
        val prefs = getSharedPreferences("M3uParserPrefs", Context.MODE_PRIVATE)
        val currentUsername = prefs.getString("username", "FerreiraLindote") ?: "FerreiraLindote"
        val currentPassword = prefs.getString("password", "123digalaoutravez1") ?: "123digalaoutravez1"

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Alterar Credenciais")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 20, 35, 20)
        }

        val etUsername = EditText(this).apply {
            hint = "Utilizador"
            setText(currentUsername)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        
        val etPassword = EditText(this).apply {
            hint = "Senha"
            setText(currentPassword)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        layout.addView(etUsername)
        layout.addView(etPassword)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { dialog, _ ->
            val newUsername = etUsername.text.toString().trim()
            val newPassword = etPassword.text.toString().trim()

            if (newUsername.isNotEmpty() && newPassword.isNotEmpty()) {
                prefs.edit().apply {
                    putString("username", newUsername)
                    putString("password", newPassword)
                    putLong("last_download_time", 0L)
                    putLong("last_epg_download_time", 0L)
                }.apply()

                XmltvManager.clearCache()
                Toast.makeText(this, "Credenciais guardadas! A atualizar...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadData()
            } else {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.cancel()
        }

        builder.create().show()
    }

    private fun reloadCurrentChannel() {
        if (currentPlayingIndex != -1) {
            playChannel(currentPlayingIndex)
        }
    }

    private fun syncMenuToCurrentChannel() {
        if (currentPlayingIndex == -1 || currentlySelectedStreams.isEmpty()) return

        val currentChannel = currentlySelectedStreams.getOrNull(currentPlayingIndex) ?: return
        val categoryId = currentChannel.categoryId

        // Visual selection highlight for current category
        categoryAdapter.setSelectedCategory(categoryId)

        // Sync the channel adapter to this category without resetting scroll instantly
        filterChannels(categoryId, false)

        // Scroll left pane to the selected category
        val catIndex = allCategories.indexOfFirst { it.id == categoryId }
        if (catIndex != -1) {
            rvCategories.scrollToPosition(catIndex)
        }

        // Scroll right pane to the current channel and request focus
        val targetIdx = currentlySelectedStreams.indexOfFirst { it.streamId == currentChannel.streamId }
        val focusIndex = if (targetIdx != -1) targetIdx else currentPlayingIndex

        rvChannels.post {
            rvChannels.scrollToPosition(focusIndex)
            rvChannels.postDelayed({
                val holder = rvChannels.findViewHolderForAdapterPosition(focusIndex)
                if (holder != null) {
                    holder.itemView.requestFocus()
                } else {
                    val layoutManager = rvChannels.layoutManager
                    val view = layoutManager?.findViewByPosition(focusIndex)
                    view?.requestFocus() ?: rvChannels.requestFocus()
                }
            }, 100)
        }
    }

    private fun showMenu() {
        menuOverlay.visibility = View.VISIBLE
        syncMenuToCurrentChannel()
    }

    private fun hideMenu() {
        menuOverlay.visibility = View.GONE
        playerView.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (menuOverlay.visibility == View.VISIBLE) {
            // Menu is open. Back or Menu closes it.
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                hideMenu()
                return true
            }
        } else {
            // Menu is closed.
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    showMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    playChannel(currentPlayingIndex - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    playChannel(currentPlayingIndex + 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getSortIndex(name: String): Int {
        val upper = name.uppercase()
        val isPortuguese = upper.startsWith("PT") || 
                           upper.contains("PORTUGAL") || 
                           upper.contains("RTP") || 
                           upper.contains("SIC") || 
                           upper.contains("TVI") || 
                           upper.contains("SPORT TV") || 
                           upper.contains("SPORTTV") || 
                           upper.contains("DAZN") || 
                           upper.contains("ELEVEN")

        if (!isPortuguese) {
            return 1000
        }

        val clean = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase()
            .replace("pt:", "")
            .replace("pt -", "")
            .replace("pt :", "")
            .replace("fhd", "")
            .replace("hd", "")
            .replace("sd", "")
            .replace("4k", "")
            .replace("hevc", "")
            .replace("h265", "")
            .replace("h264", "")
            .replace("1080p", "")
            .replace("(teste)", "")
            .replace("(indisponivel)", "")
            .trim()

        if (clean.matches(Regex("^rtp\\s*1\\b.*"))) return 1
        if (clean.matches(Regex("^rtp\\s*2\\b.*"))) return 2
        if (clean.startsWith("sic") && !clean.contains("not") && !clean.contains("rad") && !clean.contains("mul") && !clean.contains("car") && !clean.contains(" k")) return 3
        if (clean.startsWith("tvi") && !clean.contains("fic") && !clean.contains("rea") && !clean.contains("24") && !clean.contains("pla")) return 4
        if (clean.contains("sic") && clean.contains("not")) return 5
        if (clean.matches(Regex("^rtp\\s*3\\b.*"))) return 6
        if (clean.contains("porto") && clean.contains("canal")) return 7
        if (clean.contains("tvi") && clean.contains("fic")) return 8
        if (clean.contains("cnn")) return 9
        if (clean.contains("cmtv") || (clean.contains("cm") && clean.contains("tv"))) return 10
        if (clean.contains("rtp") && clean.contains("mem")) return 11
        if (clean.contains("sic") && clean.contains("mul")) return 12
        if (clean.contains("sic") && clean.contains("rad")) return 13
        if (clean.contains("sic") && clean.contains("car")) return 14
        if (clean.contains("tvi") && clean.contains("rea")) return 15
        if (clean.contains("canal q")) return 16
        if (clean.contains("globo")) return 17
        if (clean.contains("rtp") && (clean.contains("aco") || clean.contains("açor"))) return 18
        if (clean.contains("rtp") && clean.contains("mad")) return 19
        if (clean.contains("canal 11") || clean.contains("canal11")) return 20

        if (clean.contains("sport") && clean.contains("tv")) {
            if (clean.contains("1")) return 21
            if (clean.contains("2")) return 22
            if (clean.contains("3")) return 23
            if (clean.contains("4")) return 24
            if (clean.contains("5")) return 25
            if (clean.contains("6")) return 26
            return 200
        }
        if (clean.contains("dazn") || clean.contains("eleven")) {
            if (clean.contains("1")) return 27
            if (clean.contains("2")) return 28
            if (clean.contains("3")) return 29
            if (clean.contains("4")) return 30
            if (clean.contains("5")) return 31
            if (clean.contains("6")) return 32
            return 201
        }
        if (clean.contains("eurosport")) {
            if (clean.contains("1")) return 33
            if (clean.contains("2")) return 34
            return 202
        }
        if (clean.contains("panda") && clean.contains("kids")) return 36
        if (clean.contains("panda")) return 35
        if (clean.contains("cartoon")) return 37
        if (clean.contains("disney") && clean.contains("jr")) return 39
        if (clean.contains("disney")) return 38
        if (clean.contains("nickelodeon")) return 40
        if (clean.contains("sic k")) return 41

        if (clean.contains("hollywood")) return 42
        if (clean.contains("cinemundo")) return 43
        if (clean.contains("axn") && clean.contains("white")) return 45
        if (clean.contains("axn") && clean.contains("mov")) return 46
        if (clean.contains("axn")) return 44
        if (clean.contains("fox") && clean.contains("life")) return 48
        if (clean.contains("fox") && clean.contains("cri")) return 49
        if (clean.contains("fox") && clean.contains("mov")) return 50
        if (clean.contains("fox") && clean.contains("com")) return 51
        if (clean.contains("fox")) return 47
        if (clean.contains("star") && clean.contains("life")) return 48
        if (clean.contains("star") && clean.contains("cri")) return 49
        if (clean.contains("star") && clean.contains("mov")) return 50
        if (clean.contains("star") && clean.contains("com")) return 51
        if (clean.contains("star")) return 47
        if (clean.contains("syfy")) return 52
        if (clean.contains("amc")) return 53

        if (clean.contains("national") || clean.contains("nat geo") || clean.contains("natgeo")) return 54
        if (clean.contains("discovery")) return 55
        if (clean.contains("odisseia")) return 56
        if (clean.contains("history") || clean.contains("historia")) return 57

        return 1000
    }

    override fun onStart() {
        super.onStart()
        mPlayer?.play()
        playbackCheckHandler.post(checkPlaybackRunnable)
    }

    override fun onStop() {
        super.onStop()
        mPlayer?.pause()
        playbackCheckHandler.removeCallbacks(checkPlaybackRunnable)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Previne fechar a aplicação acidentalmente com a tecla BACK no comando.
        if (menuOverlay.visibility == View.GONE) {
            showMenu()
        } else {
            hideMenu()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        epgHandler.removeCallbacks(hideEpgRunnable)
        mPlayer?.let {
            it.stop()
            it.detachViews()
            it.release()
        }
        mPlayer = null
        libVlc?.release()
        libVlc = null
    }
}
