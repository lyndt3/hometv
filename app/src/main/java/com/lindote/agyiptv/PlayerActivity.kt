package com.lindote.agyiptv

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.widget.Toast

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        progressBar = findViewById(R.id.player_progress)

        val streamId = intent.getIntExtra("stream_id", -1)
        val streamUrl = intent.getStringExtra("stream_url")
        if (streamUrl != null && streamUrl.isNotEmpty()) {
            initializePlayer(streamUrl)
        } else if (streamId != -1) {
            val url = XtreamClient.getStreamUrl(streamId)
            initializePlayer(url)
        } else {
            finish()
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(url: String) {
        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            progressBar.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            progressBar.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            progressBar.visibility = View.GONE
                        }
                        Player.STATE_IDLE -> {
                            // Idle state
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@PlayerActivity, "Erro ao reproduzir: ${error.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            })
        }
        playerView.player = player
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
