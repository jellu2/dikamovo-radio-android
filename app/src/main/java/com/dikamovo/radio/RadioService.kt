package com.dikamovo.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
import java.util.Calendar

class RadioService : Service() {
    inner class LocalBinder : Binder() { fun getService(): RadioService = this@RadioService }
    private val binder = LocalBinder()
    lateinit var dbHelper: RadioDbHelper
    lateinit var engine: DualDeckEngine
    private val queue = ArrayDeque<RadioTrack>()
    private var songsSinceShout = 0
    private val triggeredBreaks = mutableSetOf<String>()
    var isAutoDjRunning = false
        private set
    var uiListener: ((String, RadioTrack?, Long, Long, String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("dikamovo_onair", "Dikamovo Radio On-Air", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        dbHelper = RadioDbHelper(this)
        dbHelper.syncDatabaseFromSharedStorage()
        engine = DualDeckEngine(this, { if (isAutoDjRunning) playNextInRotation() }) { deck, track, pos, dur ->
            uiListener?.invoke(deck, track, pos, dur, dbHelper.currentSlotName())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(101, buildNotification("94.3 4N Radio — Standby"))
        return START_STICKY
    }

    fun startAutoDj() { isAutoDjRunning = true; playNextInRotation() }
    fun stopAutoDj() { isAutoDjRunning = false; engine.stopAll(); updateNotification("94.3 4N Radio — Stopped") }

    fun playNextInRotation() {
        checkScheduledBreaks()
        val next = if (queue.isNotEmpty()) queue.removeFirst()
        else if (songsSinceShout >= 2) { songsSinceShout = 0; dbHelper.pickElement("shout") ?: dbHelper.pickNextMusic() }
        else { songsSinceShout++; dbHelper.pickNextMusic() }
        if (next != null) {
            engine.playTrack(next)
            updateNotification("ON AIR: ${next.title}")
        }
    }

    private fun checkScheduledBreaks() {
        val cal = Calendar.getInstance()
        val min = cal.get(Calendar.MINUTE)
        val key = "${cal.get(Calendar.YEAR)}${cal.get(Calendar.DAY_OF_YEAR)}${cal.get(Calendar.HOUR_OF_DAY)}:$min"
        if (key in triggeredBreaks) return
        if (min == 0 || min == 15 || min == 30) {
            val ads = dbHelper.dueAdsForCurrentMinute()
            if (min != 0 && ads.isEmpty()) return
            triggeredBreaks.add(key)
            val items = mutableListOf<RadioTrack>()
            if (min == 0) {
                dbHelper.pickElement("jingle")?.let { items.add(it) }
                dbHelper.pickElement("id")?.let { items.add(it) }
                items.addAll(ads)
                if (ads.isNotEmpty()) dbHelper.pickElement("stinger")?.let { items.add(it) }
            } else {
                dbHelper.pickElement("sponsor")?.let { items.add(it) }
                items.addAll(ads)
                dbHelper.pickElement("stinger")?.let { items.add(it) }
            }
            items.asReversed().forEach { queue.addFirst(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "dikamovo_onair")
            .setContentTitle("Dikamovo Radio 94.3 FM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(101, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { engine.release(); super.onDestroy() }
}
