package com.dikamovo.radio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.util.Calendar

data class RadioTrack(
    val id: Int,
    val kind: String,
    val title: String,
    val artist: String,
    val localPath: String,
    val cueInMs: Long = 0L,
    val cueNextMs: Long = 0L,
    val cueEndMs: Long = 0L,
    val fadeInSec: Float = 0f,
    val fadeOutSec: Float = 0f
)

class RadioDbHelper(private val context: Context) {
    private val localDbFile = File(context.filesDir, "radio_studio.db")
    private val fileIndex = mutableMapOf<String, String>()

    fun syncDatabaseFromSharedStorage(): Boolean {
        val sd = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(sd, "Dikamovo/Dikamovo Radio/radio_studio.db"),
            File(sd, "Dikamovo/radio_studio.db"),
            File(sd, "Download/radio_studio.db")
        )
        val src = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return localDbFile.exists()
        src.copyTo(localDbFile, overwrite = true)
        rebuildAudioFileIndex()
        return true
    }

    fun rebuildAudioFileIndex() {
        fileIndex.clear()
        val sd = Environment.getExternalStorageDirectory()
        val scanDirs = listOf(File(sd, "Dikamovo"), File(sd, "Music"), File(sd, "Download"))
        val exts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
        for (dir in scanDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().forEach { f ->
                if (f.isFile && f.extension.lowercase() in exts) {
                    fileIndex[f.name.lowercase()] = f.absolutePath
                }
            }
        }
    }

    private fun resolveAndroidPath(winOrRawPath: String?): String? {
        if (winOrRawPath.isNullOrBlank()) return null
        val direct = File(winOrRawPath)
        if (direct.exists()) return direct.absolutePath
        val baseName = winOrRawPath.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return fileIndex[baseName]
    }

    private fun openDb(): SQLiteDatabase? {
        if (!localDbFile.exists()) syncDatabaseFromSharedStorage()
        if (!localDbFile.exists()) return null
        return SQLiteDatabase.openDatabase(localDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun attachCuePoints(db: SQLiteDatabase, origPath: String, track: RadioTrack): RadioTrack {
        val baseName = origPath.substringAfterLast('\\').substringAfterLast('/')
        val cur = db.rawQuery(
            "SELECT cue_in, cue_next, cue_end, fade_in, fade_out FROM cue_points WHERE file_path = ? OR file_path LIKE ?",
            arrayOf(origPath, "%$baseName")
        )
        cur.use {
            if (it.moveToFirst()) {
                return track.copy(
                    cueInMs = (it.getDouble(0) * 1000).toLong().coerceAtLeast(0L),
                    cueNextMs = (it.getDouble(1) * 1000).toLong().coerceAtLeast(0L),
                    cueEndMs = (it.getDouble(2) * 1000).toLong().coerceAtLeast(0L),
                    fadeInSec = it.getFloat(3),
                    fadeOutSec = it.getFloat(4)
                )
            }
        }
        return track
    }

    fun currentSlotName(): String {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) return "Sunday Classic"
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 4..5 -> "Max Surban & Bisaya Classics"
            in 6..8 -> "Morning Mix"
            9 -> "Love Songs"
            10 -> "Mid-Morning Mix"
            11 -> "Dance Music"
            12 -> "Noon Oldies Hits"
            in 13..14 -> "Love Songs Pangbiyahi"
            in 15..16 -> "Slow Rock, Rock, Folk & Country"
            in 17..18 -> "Philippines Top Hits"
            19 -> "R&B"
            in 20..21 -> "Slow / Pangkatulog"
            else -> "Late Night Slow"
        }
    }

    fun pickNextMusic(): RadioTrack? {
        val db = openDb() ?: return fallbackFromStorage()
        db.use {
            val cal = Calendar.getInstance()
            val isSunday = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val curYear = cal.get(Calendar.YEAR)
            val whereClause = when {
                isSunday || hour == 12 -> "is_active = 1 AND (year IS NULL OR year < ${curYear - 3})"
                hour in 17..18 -> "is_active = 1 AND (category LIKE '%phtop%' OR category LIKE '%new%') AND (year IS NULL OR year >= ${curYear - 1})"
                else -> "is_active = 1"
            }
            val list = mutableListOf<Pair<String, RadioTrack>>()
            val c = it.rawQuery("SELECT id, title, artist, file_path FROM music WHERE $whereClause ORDER BY RANDOM() LIMIT 30", null)
            c.use { cur ->
                while (cur.moveToNext()) {
                    val orig = cur.getString(3)
                    val resolved = resolveAndroidPath(orig)
                    if (resolved != null) {
                        list.add(orig to RadioTrack(cur.getInt(0), "music", cur.getString(1) ?: "Unknown", cur.getString(2) ?: "4N Radio", resolved))
                    }
                }
            }
            if (list.isNotEmpty()) {
                val (orig, chosen) = list.random()
                return attachCuePoints(it, orig, chosen)
            }
        }
        return fallbackFromStorage()
    }

    fun pickElement(kind: String): RadioTrack? {
        val db = openDb() ?: return null
        db.use {
            val c = it.rawQuery("SELECT id, title, file_path FROM station_elements WHERE kind = ? AND is_active = 1 ORDER BY RANDOM() LIMIT 10", arrayOf(kind))
            c.use { cur ->
                while (cur.moveToNext()) {
                    val orig = cur.getString(2)
                    val resolved = resolveAndroidPath(orig)
                    if (resolved != null) {
                        return attachCuePoints(it, orig, RadioTrack(cur.getInt(0), kind, "[${kind.uppercase()}] ${cur.getString(1) ?: kind}", "94.3 4N Radio", resolved))
                    }
                }
            }
        }
        return null
    }

    fun dueAdsForCurrentMinute(): List<RadioTrack> {
        val db = openDb() ?: return emptyList()
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val result = mutableListOf<RadioTrack>()
        db.use {
            val c = it.rawQuery("SELECT a.id, a.title, a.file_path, s.hours, s.minutes FROM advertisements a JOIN ad_schedules s ON a.id = s.ad_id WHERE a.is_active = 1", null)
            c.use { cur ->
                while (cur.moveToNext()) {
                    val hoursStr = cur.getString(3) ?: ""
                    val minsStr = cur.getString(4) ?: ""
                    val minMatch = minsStr.split(",").mapNotNull { m -> m.trim().toIntOrNull() }.contains(minute)
                    val hrMatch = hoursStr.isBlank() || hoursStr.split(",").mapNotNull { h -> h.trim().toIntOrNull() }.contains(hour)
                    if (minMatch && hrMatch) {
                        val orig = cur.getString(2)
                        val resolved = resolveAndroidPath(orig)
                        if (resolved != null) {
                            result.add(attachCuePoints(it, orig, RadioTrack(cur.getInt(0), "ad", "[AD] ${cur.getString(1)}", "Commercial", resolved)))
                        }
                    }
                }
            }
        }
        return result
    }

    private fun fallbackFromStorage(): RadioTrack? {
        if (fileIndex.isEmpty()) rebuildAudioFileIndex()
        val anyPath = fileIndex.values.randomOrNull() ?: return null
        val f = File(anyPath)
        return RadioTrack(0, "music", f.nameWithoutExtension, "4N Radio Local", anyPath)
    }
}
