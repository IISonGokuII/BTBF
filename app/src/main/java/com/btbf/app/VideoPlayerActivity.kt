package com.btbf.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.btbf.app.databinding.ActivityVideoPlayerBinding

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_TITLE = "video_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        if (videoUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Keine Video-URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializePlayer(videoUrl)
    }

    private fun initializePlayer(videoUrl: String) {
        val referer = intent.getStringExtra(EXTRA_REFERER) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            ?: "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(
                    buildMap {
                        if (referer.isNotEmpty()) put("Referer", referer)
                        put("Origin", referer.takeIf { it.isNotEmpty() }
                            ?.let { Uri.parse(it).let { u -> "${u.scheme}://${u.host}" } } ?: "")
                    }.filterValues { it.isNotEmpty() }
                )
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)

            val mediaSource: MediaSource = when {
                videoUrl.contains(".m3u8", ignoreCase = true) -> {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(MediaItem.fromUri(videoUrl))
                }
                videoUrl.contains(".mpd", ignoreCase = true) -> {
                    // DASH - fallback to progressive
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(videoUrl))
                }
                else -> {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(videoUrl))
                }
            }

            exoPlayer.setMediaSource(mediaSource)

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    binding.loadingSpinner.visibility = when (playbackState) {
                        Player.STATE_BUFFERING -> View.VISIBLE
                        else -> View.GONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val hint = if (videoUrl.contains(".mpd", ignoreCase = true)) {
                        "\n${getString(R.string.dash_play_error)}"
                    } else ""
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        "Wiedergabe-Fehler: ${error.localizedMessage}$hint",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            })

            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        }
    }

    // ==================== FIRETV CONTROLS ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                p.seekTo(maxOf(0, p.currentPosition - 10000))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                p.seekTo(minOf(p.duration, p.currentPosition + 10000))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==================== SYSTEM UI ====================

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
