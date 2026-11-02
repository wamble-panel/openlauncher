package com.openlauncher.app.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * MMI's own music player for USB sticks and SD cards (replaces the vendor Music app).
 * The library comes from Android's media index, which covers every attached volume;
 * playback uses the chip's hardware decoders through MediaPlayer, gapless (the next
 * song is prepared before the current one ends). A media session makes the steering
 * keys and MMI's Now playing work like for any other player.
 */
object UsbMusic {
    data class Song(
        val id: Long, val title: String, val artist: String, val album: String,
        val albumId: Long, val durationMs: Long, val folder: String,
    )

    data class State(val queue: List<Song> = emptyList(), val index: Int = -1, val playing: Boolean = false) {
        val song: Song? get() = queue.getOrNull(index)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private val _library = MutableStateFlow<List<Song>>(emptyList())
    val library: StateFlow<List<Song>> = _library
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile var scanning = false
        private set

    private var player: MediaPlayer? = null
    private var next: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null

    /** True while MMI's player is the one the user last chose (so controls go here). */
    val active: Boolean get() = _state.value.index >= 0

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        rescan()
    }

    /** Reads the media index again (USB stick plugged in or changed). */
    fun rescan() {
        val ctx = app ?: return
        if (scanning) return
        scanning = true
        scope.launch {
            val out = ArrayList<Song>()
            val volumes = runCatching { MediaStore.getExternalVolumeNames(ctx) }.getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL))
            for (vol in volumes) {
                runCatching {
                    ctx.contentResolver.query(
                        MediaStore.Audio.Media.getContentUri(vol),
                        arrayOf(
                            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.RELATIVE_PATH,
                        ),
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000",
                        null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE",
                    )?.use { c ->
                        while (c.moveToNext()) {
                            out += Song(
                                c.getLong(0), c.getString(1) ?: "Unknown", c.getString(2)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                                c.getString(3) ?: "", c.getLong(4), c.getLong(5), (c.getString(6) ?: "").trimEnd('/'),
                            )
                        }
                    }
                }
            }
            _library.value = out.distinctBy { it.id }
            scanning = false
        }
    }

    private fun uri(s: Song): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)

    /** Plays [queue] from [index] (a list, an album, an artist, a folder). */
    fun play(queue: List<Song>, index: Int) {
        if (queue.isEmpty()) return
        _state.value = State(queue, index.coerceIn(0, queue.size - 1), false)
        start()
    }

    private fun start() {
        val ctx = app ?: return
        val s = _state.value.song ?: return
        release()
        if (!requestFocus()) return
        // The car's audio source to Android media, as the vendor music app asks for it.
        CarCore.write(40465, 192, 3)
        val p = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(ctx, uri(s))
            setOnCompletionListener { onCompleted() }
            setOnErrorListener { _, _, _ -> skip(1); true }
        }
        runCatching { p.prepare(); p.start() }.onFailure { skip(1); return }
        player = p
        _state.value = _state.value.copy(playing = true)
        prepareNext()
        publish()
    }

    // Gapless: the next song is ready and handed to the player before this one ends.
    private fun prepareNext() {
        val ctx = app ?: return
        val st = _state.value
        val n = st.queue.getOrNull(st.index + 1) ?: return
        scope.launch {
            val mp = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    setDataSource(ctx, uri(n))
                    prepare()
                }
            }.getOrNull() ?: return@launch
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val cur = player
                if (cur == null || _state.value.index != st.index) { mp.release(); return@withContext }
                next = mp
                runCatching { cur.setNextMediaPlayer(mp) }
            }
        }
    }

    private fun onCompleted() {
        val n = next
        val st = _state.value
        if (n != null && st.index + 1 < st.queue.size) {
            // The next player is already running (gapless).
            player?.release()
            player = n
            next = null
            n.setOnCompletionListener { onCompleted() }
            n.setOnErrorListener { _, _, _ -> skip(1); true }
            _state.value = st.copy(index = st.index + 1, playing = true)
            prepareNext()
            publish()
        } else if (st.index + 1 < st.queue.size) {
            _state.value = st.copy(index = st.index + 1)
            start()
        } else {
            _state.value = st.copy(playing = false)
            publish()
        }
    }

    fun toggle() {
        val p = player ?: run { if (_state.value.song != null) start(); return }
        if (p.isPlaying) {
            p.pause()
            _state.value = _state.value.copy(playing = false)
        } else {
            if (!requestFocus()) return
            p.start()
            _state.value = _state.value.copy(playing = true)
        }
        publish()
    }

    fun pause() { if (player?.isPlaying == true) toggle() }

    fun skip(dir: Int) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        // Back within the first 3 s goes to the previous song, later restarts this one.
        if (dir < 0 && (player?.currentPosition ?: 0) > 3000) { seek(0); return }
        val i = st.index + dir
        if (i !in st.queue.indices) return
        _state.value = st.copy(index = i)
        start()
    }

    fun seek(ms: Long) { runCatching { player?.seekTo(ms.toInt()) }; publish() }

    val positionMs: Long get() = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L

    /** Stops MMI's player (another source was chosen). */
    fun stop() {
        release()
        _state.value = State()
        session?.isActive = false
        abandonFocus()
    }

    private fun release() {
        next?.release(); next = null
        player?.release(); player = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
        }
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { focus = it }
        return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }

    /** Cover art for the current song, small (decoded at shown size). */
    fun cover(): Bitmap? {
        val ctx = app ?: return null
        val s = _state.value.song ?: return null
        return runCatching { ctx.contentResolver.loadThumbnail(uri(s), android.util.Size(400, 400), null) }.getOrNull()
    }

    // Media session: steering-wheel keys, the vendor key paths and MMI's Now playing.
    private fun publish() {
        val ctx = app ?: return
        val s = session ?: MediaSession(ctx, "MMI music").also { ms ->
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = toggle()
                override fun onPause() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onSeekTo(pos: Long) = seek(pos)
            })
            session = ms
        }
        val st = _state.value
        val song = st.song
        s.setMetadata(MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, song?.title ?: "")
            putString(MediaMetadata.METADATA_KEY_ARTIST, song?.artist ?: "")
            putString(MediaMetadata.METADATA_KEY_ALBUM, song?.album ?: "")
            putLong(MediaMetadata.METADATA_KEY_DURATION, song?.durationMs ?: 0)
        }.build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, positionMs, 1f)
            .build())
        s.isActive = song != null
    }
}
