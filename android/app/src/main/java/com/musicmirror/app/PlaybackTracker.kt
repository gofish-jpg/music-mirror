package com.musicmirror.app

import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs

class PlaybackTracker(
    private val database: MusicDatabase,
    private val deviceId: String,
    private val onDirty: () -> Unit
) {
    private var current: TrackInfo? = null
    private var listenId: String? = null
    private var state: Int = PlaybackState.STATE_NONE
    private var positionMs = 0L
    private var durationMs = 0L
    private var listenedMs = 0L
    private var speed = 1f
    private var lastRealtime = SystemClock.elapsedRealtime()
    private var lastPersistRealtime = lastRealtime

    @Synchronized
    fun onMetadata(track: TrackInfo) {
        if (track.title.isBlank()) return
        if (current?.key == track.key) {
            current = track
            if (track.durationMs > 0) durationMs = track.durationMs
            return
        }
        finishCurrent()
        current = track
        durationMs = track.durationMs
        positionMs = 0
        listenedMs = 0
        state = PlaybackState.STATE_NONE
        lastRealtime = SystemClock.elapsedRealtime()
        listenId = database.startListen(track, deviceId)
        onDirty()
    }

    @Synchronized
    fun onPlaybackState(next: PlaybackState?) {
        if (next == null || listenId == null) return
        val nowRealtime = SystemClock.elapsedRealtime()
        accrue(nowRealtime)

        val repeatThreshold = if (durationMs > 0) (durationMs * 0.50).toLong() else 30_000L
        if (state == PlaybackState.STATE_PLAYING && next.state == PlaybackState.STATE_PLAYING && next.position in 0..4_999 && positionMs >= repeatThreshold) {
            val repeatedTrack = current
            finishCurrent()
            if (repeatedTrack != null) onMetadata(repeatedTrack)
            state = next.state
            positionMs = next.position.coerceAtLeast(0)
            speed = next.playbackSpeed.takeIf { it > 0f } ?: 1f
            lastRealtime = nowRealtime
            listenId?.let { database.addEvent(it, "play", positionMs, payload = "repeat") }
            persist()
            onDirty()
            return
        }

        val expectedPosition = if (state == PlaybackState.STATE_PLAYING) {
            positionMs + ((nowRealtime - lastRealtime) * speed).toLong()
        } else positionMs
        val nextPosition = next.position.coerceAtLeast(0)
        if (positionMs > 0 && nextPosition > 0 && abs(nextPosition - expectedPosition) > 7_000) {
            database.addEvent(listenId!!, "seek", nextPosition, payload = "from=$expectedPosition")
        }

        if (state != next.state) {
            val event = when (next.state) {
                PlaybackState.STATE_PLAYING -> "play"
                PlaybackState.STATE_PAUSED -> "pause"
                PlaybackState.STATE_STOPPED -> "stop"
                PlaybackState.STATE_SKIPPING_TO_NEXT -> "skip_next"
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skip_previous"
                PlaybackState.STATE_BUFFERING -> "buffering"
                else -> null
            }
            event?.let { database.addEvent(listenId!!, it, nextPosition) }
        }

        state = next.state
        positionMs = nextPosition
        speed = next.playbackSpeed.takeIf { it > 0f } ?: 1f
        lastRealtime = nowRealtime
        persist()
        onDirty()
    }

    @Synchronized
    fun pulse() {
        if (listenId == null) return
        val now = SystemClock.elapsedRealtime()
        accrue(now)
        if (now - lastPersistRealtime >= 15_000) persist()
    }

    @Synchronized
    fun shutdown() = finishCurrent()

    private fun accrue(nowRealtime: Long) {
        if (state == PlaybackState.STATE_PLAYING) {
            val elapsed = (nowRealtime - lastRealtime).coerceIn(0, 30_000)
            listenedMs += elapsed
            positionMs += (elapsed * speed).toLong()
        }
        lastRealtime = nowRealtime
    }

    private fun persist() {
        val id = listenId ?: return
        database.updateProgress(id, listenedMs, positionMs, durationMs)
        lastPersistRealtime = SystemClock.elapsedRealtime()
    }

    private fun finishCurrent() {
        val id = listenId ?: return
        accrue(SystemClock.elapsedRealtime())
        val ratio = if (durationMs > 0) listenedMs.toDouble() / durationMs else 0.0
        val completed = ratio >= 0.80 || (durationMs > 0 && positionMs >= durationMs * 0.95)
        database.finishListen(id, listenedMs, positionMs, durationMs, completed)
        current = null
        listenId = null
        state = PlaybackState.STATE_NONE
        onDirty()
    }
}
