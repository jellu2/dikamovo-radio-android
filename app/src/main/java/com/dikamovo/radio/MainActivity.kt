package com.dikamovo.radio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var radioService: RadioService? = null
    private lateinit var slotTv: TextView
    private lateinit var deckTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var timeTv: TextView

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as RadioService.LocalBinder).getService()
            radioService = svc
            svc.uiListener = { deck, track, pos, dur, slot ->
                runOnUiThread {
                    slotTv.text = "SLOT: $slot"
                    deckTv.text = "ACTIVE DECK: [$deck]"
                    titleTv.text = track?.title ?: "READY / STANDBY"
                    timeTv.text = "${fmt(pos)} / ${fmt(dur)}"
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { radioService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 10)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val header = TextView(this).apply { text = "DIKAMOVO RADIO STUDIO"; textSize = 22f; setPadding(0, 0, 0, 24) }
        slotTv = TextView(this).apply { text = "SLOT: Loading..."; textSize = 16f }
        deckTv = TextView(this).apply { text = "ACTIVE DECK: [A]"; textSize = 15f; setPadding(0, 12, 0, 8) }
        titleTv = TextView(this).apply { text = "READY"; textSize = 20f; setPadding(0, 16, 0, 16) }
        timeTv = TextView(this).apply { text = "00:00 / 00:00"; textSize = 18f; setPadding(0, 0, 0, 32) }
        val btnStart = Button(this).apply { text = "▶ START AUTO-DJ"; setOnClickListener { radioService?.startAutoDj() } }
        val btnNext = Button(this).apply { text = "⏭ CROSSFADE NEXT"; setOnClickListener { radioService?.playNextInRotation() } }
        val btnStop = Button(this).apply { text = "■ STOP"; setOnClickListener { radioService?.stopAutoDj() } }
        val btnSync = Button(this).apply {
            text = "🔄 SYNC DB & AUDIO FOLDERS"
            setOnClickListener {
                val ok = radioService?.dbHelper?.syncDatabaseFromSharedStorage() ?: false
                Toast.makeText(this@MainActivity, if (ok) "DB & CUE Points Synced!" else "Wala makit-i ang radio_studio.db", Toast.LENGTH_SHORT).show()
            }
        }
        listOf(header, slotTv, deckTv, titleTv, timeTv, btnStart, btnNext, btnStop, btnSync).forEach { root.addView(it) }
        setContentView(root)
        val intent = Intent(this, RadioService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun fmt(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(sec / 60, sec % 60)
    }

    override fun onDestroy() { unbindService(conn); super.onDestroy() }
}
