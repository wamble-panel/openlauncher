package com.openlauncher.app.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openlauncher.app.model.NowPlayingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaListenerService : NotificationListenerService() {

    private var activeController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?)   = refreshNowPlaying()
        override fun onSessionDestroyed()                          = refreshNowPlaying()
    }

    // Bluetooth music apps on head units often start a media session without
    // posting a notification, so sessions are watched directly as well.
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshNowPlaying() }

    private fun watchSessions(on: Boolean) {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        runCatching {
            if (on) msm.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(this, MediaListenerService::class.java))
            else msm.removeOnActiveSessionsChangedListener(sessionsListener)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected.value = true
        watchSessions(true)
        refreshNowPlaying()
        runCatching { activeNotifications?.forEach { NavigationTracker.onPosted(it, this) } }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isConnected.value = false
        watchSessions(false)
        clearController()
        _nowPlaying.value = null
        NavigationTracker.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNowPlaying()
        sbn?.let { NavigationTracker.onPosted(it, this) }
        if (sbn != null && sbn.isClearable && !sbn.isOngoing && sbn.packageName != packageName &&
            KEEP_PREFIXES.none { sbn.packageName.startsWith(it) } && popupsHidden(this)
        ) {
            runCatching { cancelNotification(sbn.key) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNowPlaying()
        sbn?.let { NavigationTracker.onRemoved(it) }
    }

    override fun onDestroy() {
        instance = null
        watchSessions(false)
        clearController()
        // Clear the static flow so the UI doesn't keep showing a dead session
        // (and pinning its album-art bitmap) after the service is killed
        _nowPlaying.value = null
        super.onDestroy()
    }

    private fun clearController() {
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
    }

    private fun refreshNowPlaying() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (_: SecurityException) {
            emptyList()
        }

        val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()

        if (active == null) {
            clearController()
            _nowPlaying.value = null
            return
        }

        // getActiveSessions returns NEW MediaController instances on every call —
        // compare session tokens, not references, or we churn callbacks and
        // recompose the UI on every notification system-wide.
        if (active.sessionToken != activeController?.sessionToken) {
            clearController()
            activeController = active
            active.registerCallback(
                controllerCallback,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }

        updateFromController(activeController)
    }

    private fun updateFromController(controller: MediaController?) {
        if (controller == null) { _nowPlaying.value = null; return }
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        // Prefer the largest bitmap the source provides; many apps put a
        // downscaled image in ALBUM_ART and the full one in ART (or vice versa)
        val art = try {
            listOfNotNull(
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ).maxByOrNull { it.width * it.height }
        } catch (_: Exception) { null }
        val artUri = meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        // Skip redundant emissions: metadata bitmaps parcel into fresh instances on
        // every read, so a plain data-class compare would never match and every
        // notification would force a recomposition + art redraw.
        val prev = _nowPlaying.value
        if (prev != null &&
            prev.controller?.sessionToken == controller.sessionToken &&
            prev.title == title && prev.artist == artist &&
            prev.isPlaying == isPlaying && prev.artUri == artUri &&
            (prev.albumArt != null) == (art != null)
        ) return

        _nowPlaying.value = NowPlayingState(
            title      = title,
            artist     = artist,
            albumArt   = art,
            artUri     = artUri,
            isPlaying  = isPlaying,
            controller = controller
        )
    }

    companion object {
        private val _nowPlaying = MutableStateFlow<NowPlayingState?>(null)
        val nowPlaying: StateFlow<NowPlayingState?> = _nowPlaying
        val isConnected = MutableStateFlow(false)

        @Volatile private var instance: MediaListenerService? = null
        fun requestRefresh() { instance?.refreshNowPlaying() }

        private fun uiPrefs(context: android.content.Context) =
            context.getSharedPreferences("mmi_ui", android.content.Context.MODE_PRIVATE)

        /** Other apps' notification pop-ups are dismissed as they arrive, so they never cover MMI. */
        fun popupsHidden(context: android.content.Context) = uiPrefs(context).getBoolean("hide_popups", true)

        fun setPopupsHidden(context: android.content.Context, hidden: Boolean) {
            uiPrefs(context).edit().putBoolean("hide_popups", hidden).apply()
        }

        /** Never dismissed: ongoing ones, the car's own apps, navigation and Android itself. */
        private val KEEP_PREFIXES = listOf("com.tw.", "com.android.", "android", "com.google.android.apps.maps", "com.waze")
    }
}
