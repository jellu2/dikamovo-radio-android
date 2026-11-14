package com.dikamovo.radio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class DualDeckEngine(
    context: Context,
    private val onRequestNextTrack: (RadioTrack?) -> Unit,
    private val onStateChanged: (activeSide: String, track: RadioTrack?, posMs: Long, durMs: Long) -> Unit
) {
    private val playerA = ExoPlayer.Builder(context).build()
    private val playerB = ExoPlayer.Builder(context).build()
    private var activeIsA = true
    private var currentTrack: RadioTrack? = null
    private var nextTriggered = false
    private val handler = Handler(Looper.getMainLooper())
    var masterVolume = 1.0f
    var defaultCrossfadeMs = 3000L

    private val monitorRunnable = object : Runnable {
        override fun run() {
            val active = activePlayer()
            if (active.isPlaying) {
                val pos = active.currentPosition
                val dur = active.duration.coerceAtLeast(0L)
                val tr = currentTrack
                onStateChanged(if (activeIsA) "A" else "B", tr, pos, dur)
                if (!nextTriggered && dur > 0 && tr != null) {
                    val endMs = if (tr.cueEndMs in 1 until dur) tr.cueEndMs else dur
                    val triggerMs = when {
                        tr.cueNextMs in 1 until endMs -> tr.cueNextMs
                        tr.kind != "music" -> (endMs - 160L).coerceAtLeast(0L)
                        else -> (endMs - defaultCrossfadeMs).coerceAtLeast(0L)
                    }
                    if (pos >= triggerMs) {
                        nextTriggered = true
                        onRequestNextTrack(tr)
                    }
                }
            }
            handler.postDelayed(this, 80L)
        }
    }

    init {
        playerA.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && activeIsA && !nextTriggered) {
                    nextTriggered = true
                    onRequestNextTrack(currentTrack)
                }
            }
        })
        playerB.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !activeIsA && !nextTriggered) {
                    nextTriggered = true
                    onRequestNextTrack(currentTrack)
                }
            }
        })
        handler.post(monitorRunnable)
    }

    private fun activePlayer() = if (activeIsA) playerA else playerB

    fun playTrack(track: RadioTrack) {
        val prevTrack = currentTrack
        val oldPlayer = activePlayer()
        activeIsA = !activeIsA
        val newPlayer = activePlayer()
        currentTrack = track
        nextTriggered = false

        newPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(track.localPath))))
        newPlayer.prepare()
        if (track.cueInMs > 0) newPlayer.seekTo(track.cueInMs)

        val isElementOrAd = track.kind != "music"
        val fadeSec = when {
            isElementOrAd -> 0.25f
            prevTrack != null && prevTrack.fadeOutSec > 0.1f -> prevTrack.fadeOutSec
            else -> defaultCrossfadeMs / 1000f
        }

        if (!oldPlayer.isPlaying || fadeSec < 0.2f) {
            oldPlayer.stop()
            newPlayer.volume = masterVolume
            newPlayer.play()
            return
        }

        newPlayer.volume = if (isElementOrAd) masterVolume else 0f
        newPlayer.play()
        val steps = (fadeSec * 20).toInt().coerceAtLeast(1)
        var step = 0
        val fadeRunnable = object : Runnable {
            override fun run() {
                step++
                val ratio = (step.toFloat() / steps).coerceIn(0f, 1f)
                if (isElementOrAd) {
                    newPlayer.volume = masterVolume
                    oldPlayer.volume = masterVolume * (1f - ratio)
                } else {
                    newPlayer.volume = masterVolume * sin(ratio * Math.PI / 2).toFloat()
                    oldPlayer.volume = masterVolume * cos(ratio * Math.PI / 2).toFloat()
                }
                if (step >= steps) {
                    oldPlayer.stop()
                    oldPlayer.volume = 0f
                } else {
                    handler.postDelayed(this, 50L)
                }
            }
        }
        handler.post(fadeRunnable)
    }

    fun stopAll() {
        playerA.stop()
        playerB.stop()
        currentTrack = null
        nextTriggered = false
        onStateChanged("A", null, 0L, 0L)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerA.release()
        playerB.release()
    }
}
