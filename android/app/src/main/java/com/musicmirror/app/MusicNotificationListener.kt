package com.musicmirror.app

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

class MusicNotificationListener : NotificationListenerService() {
    private lateinit var database: MusicDatabase
    private lateinit var tracker: PlaybackTracker
    private lateinit var syncClient: SyncClient
    private lateinit var sessionManager: MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val youtubeMusicPackage = "com.google.android.apps.youtube.music"

    private val pulse = object : Runnable {
        override fun run() {
            tracker.pulse()
            syncClient.schedule()
            mainHandler.postDelayed(this, 10_000)
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refreshControllers(it.orEmpty()) }

    override fun onCreate() {
        super.onCreate()
        database = MusicDatabase(this)
        database.closeStaleSessions()
        syncClient = SyncClient(this, database)
        tracker = PlaybackTracker(database, SecretStore(this).deviceId) { syncClient.schedule() }
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, MusicNotificationListener::class.java)
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, component, mainHandler)
            refreshControllers(sessionManager.getActiveSessions(component))
        }
        mainHandler.removeCallbacks(pulse)
        mainHandler.post(pulse)
    }

    override fun onListenerDisconnected() {
        mainHandler.removeCallbacks(pulse)
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        clearControllers()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pulse)
        clearControllers()
        tracker.shutdown()
        syncClient.close()
        database.close()
        super.onDestroy()
    }

    private fun refreshControllers(controllers: List<MediaController>) {
        val musicControllers = controllers.filter { it.packageName == youtubeMusicPackage }.toSet()
        callbacks.keys.filter { it !in musicControllers }.forEach { controller ->
            callbacks.remove(controller)?.let { controller.unregisterCallback(it) }
        }
        musicControllers.filter { it !in callbacks }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    metadata?.toTrackInfo()?.let(tracker::onMetadata)
                }

                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    tracker.onPlaybackState(state)
                }

                override fun onSessionDestroyed() {
                    refreshFromSystem()
                }
            }
            callbacks[controller] = callback
            controller.registerCallback(callback, mainHandler)
            controller.metadata?.toTrackInfo()?.let(tracker::onMetadata)
            tracker.onPlaybackState(controller.playbackState)
        }
    }

    private fun refreshFromSystem() {
        val component = ComponentName(this, MusicNotificationListener::class.java)
        runCatching { refreshControllers(sessionManager.getActiveSessions(component)) }
    }

    private fun clearControllers() {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
    }

    private fun MediaMetadata.toTrackInfo(): TrackInfo? {
        val title = getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isBlank()) return null
        val artist = (getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown artist").trim()
        return TrackInfo(
            mediaId = getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = title,
            artist = artist,
            album = getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        )
    }
}
