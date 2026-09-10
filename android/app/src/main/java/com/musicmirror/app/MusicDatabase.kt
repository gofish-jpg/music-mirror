package com.musicmirror.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class MusicDatabase(context: Context) : SQLiteOpenHelper(context, "music_mirror.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE listens (
                id TEXT PRIMARY KEY,
                track_key TEXT NOT NULL,
                media_id TEXT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                listened_ms INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                last_position_ms INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                device_id TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE playback_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                listen_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                position_ms INTEGER NOT NULL DEFAULT 0,
                payload TEXT,
                synced INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(listen_id) REFERENCES listens(id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_listens_sync ON listens(synced, started_at)")
        db.execSQL("CREATE INDEX idx_events_sync ON playback_events(synced, occurred_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun closeStaleSessions(now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("ended_at", now)
            put("synced", 0)
        }
        writableDatabase.update("listens", values, "ended_at IS NULL", null)
    }

    fun startListen(track: TrackInfo, deviceId: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("track_key", track.key)
            put("media_id", track.mediaId)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("started_at", now)
            put("duration_ms", track.durationMs)
            put("device_id", deviceId)
        }
        writableDatabase.insertOrThrow("listens", null, values)
        addEvent(id, "track_start", 0, now)
        return id
    }

    fun updateProgress(id: String, listenedMs: Long, positionMs: Long, durationMs: Long) {
        val values = ContentValues().apply {
            put("listened_ms", listenedMs)
            put("last_position_ms", positionMs.coerceAtLeast(0))
            if (durationMs > 0) put("duration_ms", durationMs)
            put("synced", 0)
        }
        writableDatabase.update("listens", values, "id = ?", arrayOf(id))
    }

    fun finishListen(id: String, listenedMs: Long, positionMs: Long, durationMs: Long, completed: Boolean, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("ended_at", now)
            put("listened_ms", listenedMs)
            put("last_position_ms", positionMs.coerceAtLeast(0))
            put("duration_ms", durationMs.coerceAtLeast(0))
            put("completed", if (completed) 1 else 0)
            put("synced", 0)
        }
        writableDatabase.update("listens", values, "id = ?", arrayOf(id))
        addEvent(id, if (completed) "complete" else "track_end", positionMs, now)
    }

    fun addEvent(listenId: String, type: String, positionMs: Long, now: Long = System.currentTimeMillis(), payload: String? = null) {
        val values = ContentValues().apply {
            put("listen_id", listenId)
            put("event_type", type)
            put("occurred_at", now)
            put("position_ms", positionMs.coerceAtLeast(0))
            put("payload", payload)
        }
        writableDatabase.insert("playback_events", null, values)
    }

    fun pendingListens(limit: Int = 200): List<PendingListen> {
        val out = mutableListOf<PendingListen>()
        readableDatabase.query("listens", null, "synced = 0", null, null, null, "started_at ASC", limit.toString()).use { c ->
            while (c.moveToNext()) out += PendingListen(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                trackKey = c.getString(c.getColumnIndexOrThrow("track_key")),
                mediaId = c.stringOrNull("media_id"),
                title = c.getString(c.getColumnIndexOrThrow("title")),
                artist = c.getString(c.getColumnIndexOrThrow("artist")),
                album = c.stringOrNull("album"),
                startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                endedAt = c.longOrNull("ended_at"),
                listenedMs = c.getLong(c.getColumnIndexOrThrow("listened_ms")),
                durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                lastPositionMs = c.getLong(c.getColumnIndexOrThrow("last_position_ms")),
                completed = c.getInt(c.getColumnIndexOrThrow("completed")) == 1,
                deviceId = c.getString(c.getColumnIndexOrThrow("device_id"))
            )
        }
        return out
    }

    fun pendingEvents(limit: Int = 500): List<PendingEvent> {
        val out = mutableListOf<PendingEvent>()
        readableDatabase.query("playback_events", null, "synced = 0", null, null, null, "occurred_at ASC", limit.toString()).use { c ->
            while (c.moveToNext()) out += PendingEvent(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                listenId = c.getString(c.getColumnIndexOrThrow("listen_id")),
                type = c.getString(c.getColumnIndexOrThrow("event_type")),
                occurredAt = c.getLong(c.getColumnIndexOrThrow("occurred_at")),
                positionMs = c.getLong(c.getColumnIndexOrThrow("position_ms")),
                payload = c.stringOrNull("payload")
            )
        }
        return out
    }

    fun markSynced(listens: List<PendingListen>, eventIds: List<Long>) {
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply { put("synced", 1) }
            listens.forEach { item ->
                writableDatabase.execSQL(
                    """UPDATE listens SET synced = 1
                       WHERE id = ? AND listened_ms = ? AND last_position_ms = ?
                         AND ((ended_at IS NULL AND ? IS NULL) OR ended_at = ?)""".trimIndent(),
                    arrayOf<Any?>(item.id, item.listenedMs, item.lastPositionMs, item.endedAt, item.endedAt)
                )
            }
            eventIds.forEach { writableDatabase.update("playback_events", values, "id = ?", arrayOf(it.toString())) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun listenCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM listens", null).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun android.database.Cursor.stringOrNull(name: String): String? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getString(i)
    }

    private fun android.database.Cursor.longOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }
}
