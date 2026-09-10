package com.musicmirror.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SyncClient(context: Context, private val database: MusicDatabase) {
    private val settings = SecretStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val syncing = AtomicBoolean(false)
    @Volatile private var lastSyncAttempt = 0L

    fun schedule(force: Boolean = false, callback: ((Result<Int>) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAttempt < 60_000) return
        if (!settings.configured() || !syncing.compareAndSet(false, true)) return
        lastSyncAttempt = now
        executor.execute {
            val result = runCatching { syncOnce() }
            syncing.set(false)
            callback?.invoke(result)
        }
    }

    fun close() = executor.shutdownNow()

    private fun syncOnce(): Int {
        val listens = database.pendingListens()
        val events = database.pendingEvents()
        if (listens.isEmpty() && events.isEmpty()) return 0

        val body = JSONObject().apply {
            put("schemaVersion", 1)
            put("listens", JSONArray().apply { listens.forEach { put(it.toJson()) } })
            put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        }.toString()

        val connection = (URL(settings.serverUrl + "/v1/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${settings.ingestToken}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "MusicMirror-Android/0.1")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("Sync failed (${connection.responseCode}): $responseText")

        database.markSynced(listens, events.map { it.id })
        return listens.size
    }

    private fun PendingListen.toJson() = JSONObject().apply {
        put("id", id); put("trackKey", trackKey); putNullable("mediaId", mediaId)
        put("title", title); put("artist", artist); putNullable("album", album)
        put("startedAt", startedAt); putNullable("endedAt", endedAt)
        put("listenedMs", listenedMs); put("durationMs", durationMs); put("lastPositionMs", lastPositionMs)
        put("completed", completed); put("deviceId", deviceId)
    }

    private fun PendingEvent.toJson() = JSONObject().apply {
        put("clientId", id); put("listenId", listenId); put("type", type)
        put("occurredAt", occurredAt); put("positionMs", positionMs); putNullable("payload", payload)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
