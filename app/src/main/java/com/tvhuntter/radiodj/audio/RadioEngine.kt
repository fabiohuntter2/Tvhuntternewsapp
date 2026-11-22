package com.tvhuntter.radiodj.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tvhuntter.radiodj.core.AudioFormat
import com.tvhuntter.radiodj.core.Broadcaster
import com.tvhuntter.radiodj.core.DuckingMixer
import com.tvhuntter.radiodj.core.LiveState
import com.tvhuntter.radiodj.core.LiveStatus
import com.tvhuntter.radiodj.core.PcmRingBuffer
import com.tvhuntter.radiodj.core.StreamConfig
import com.tvhuntter.radiodj.data.MixSettings
import com.tvhuntter.radiodj.data.Prefs
import com.tvhuntter.radiodj.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.media.AudioFormat as AF

data class PlayerState(
    val playlist: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val current: Track? get() = playlist.getOrNull(index)
}

data class Levels(
    val music: Float = 0f,
    val mic: Float = 0f,
    val outL: Float = 0f,
    val outR: Float = 0f,
    val duck: Float = 1f,
)

/**
 * Coração do app: playlist + microfone -> mixer com ducking -> (retorno no fone) + (MP3 para o servidor).
 */
object RadioEngine : MusicDeck.Listener {
    private const val TAG = "RadioEngine"

    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set

    val mixer = DuckingMixer()
    private val musicRing = PcmRingBuffer(AudioFormat.SAMPLE_RATE * AudioFormat.CHANNELS) // 1 s
    private val micRing = PcmRingBuffer(AudioFormat.SAMPLE_RATE * AudioFormat.CHANNELS / 2)
    private lateinit var deck: MusicDeck
    private val mic = MicInput(micRing)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player.asStateFlow()
    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()
    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels.asStateFlow()
    private val _micOn = MutableStateFlow(false)
    val micOn: StateFlow<Boolean> = _micOn.asStateFlow()
    private val _mix = MutableStateFlow(MixSettings())
    val mix: StateFlow<MixSettings> = _mix.asStateFlow()
    private val _config = MutableStateFlow(StreamConfig())
    val config: StateFlow<StreamConfig> = _config.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile private var broadcaster: Broadcaster? = null
    @Volatile private var musicPlaying = false
    @Volatile private var monitorEnabled = true
    @Volatile private var engineRunning = false
    private var mixThread: Thread? = null
    private var currentToken = 0
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        deck = MusicDeck(app, musicRing, this)
        _config.value = prefs.loadConfig()
        setMix(prefs.loadMix(), save = false)
        _player.value = PlayerState(playlist = prefs.loadPlaylist())
    }

    private fun toast(text: String) {
        _messages.tryEmit(text)
    }

    // ------------------------------------------------------------------ motor

    fun startEngine() {
        if (engineRunning) return
        engineRunning = true
        mixThread = Thread(::mixLoop, "mixer").apply { start() }
    }

    fun shutdown() {
        stopLive()
        setMic(false)
        mic.stop()
        deck.stop()
        musicPlaying = false
        _player.update { it.copy(playing = false) }
        engineRunning = false
        mixThread?.interrupt()
        mixThread = null
    }

    private fun createMonitorTrack(): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(AudioFormat.SAMPLE_RATE, AF.CHANNEL_OUT_STEREO, AF.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AF.Builder()
                    .setSampleRate(AudioFormat.SAMPLE_RATE)
                    .setChannelMask(AF.CHANNEL_OUT_STEREO)
                    .setEncoding(AF.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, AudioFormat.BLOCK_FRAMES * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack falhou", e); null
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = AudioFormat.BLOCK_FRAMES
        val samples = frames * AudioFormat.CHANNELS
        val blockNanos = frames * 1_000_000_000L / AudioFormat.SAMPLE_RATE
        val musicBuf = ShortArray(samples)
        val micBuf = ShortArray(samples)
        val out = ShortArray(samples)
        val monitorBuf = ShortArray(samples)
        var track: AudioTrack? = null
        var next = System.nanoTime()
        var counter = 0
        try {
            while (engineRunning) {
                // --- monitor (retorno no celular) dita o ritmo quando ligado
                val wantMonitor = monitorEnabled
                if (wantMonitor && track == null) {
                    track = createMonitorTrack()?.also { it.play() }
                } else if (!wantMonitor && track != null) {
                    runCatching { track.pause(); track.flush(); track.release() }
                    track = null
                    next = System.nanoTime()
                }

                // --- música
                val hasMusic = musicPlaying
                if (hasMusic) {
                    val n = musicRing.read(musicBuf, 0, samples)
                    if (n < samples) musicBuf.fill(0, n, samples)
                }
                // --- microfone (latência máxima ~100 ms)
                val micActive = mic.isRunning
                if (micActive) {
                    micRing.trimTo(samples * 4)
                    val n = micRing.read(micBuf, 0, samples)
                    if (n < samples) micBuf.fill(0, n, samples)
                }

                mixer.mix(
                    if (hasMusic) musicBuf else null,
                    if (micActive) micBuf else null,
                    out, frames,
                    if (track != null) monitorBuf else null,
                )

                broadcaster?.submit(out.copyOf())

                if (track != null) {
                    // escrita bloqueante: o relógio da placa de som marca o tempo
                    val w = track.write(monitorBuf, 0, samples)
                    if (w < 0) {
                        runCatching { track.release() }
                        track = null
                    }
                    next = System.nanoTime()
                } else {
                    next += blockNanos
                    val wait = next - System.nanoTime()
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } else if (wait < -200_000_000L) {
                        next = System.nanoTime() // atrasou demais: realinha
                    }
                }

                counter++
                if (counter % 3 == 0) {
                    _levels.value = Levels(mixer.musicPeak, mixer.micPeak, mixer.outPeakL, mixer.outPeakR, mixer.currentDuckGain)
                }
                if (counter % 10 == 0 && hasMusic) {
                    val pos = deck.positionMs
                    _player.update { it.copy(positionMs = pos) }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "Erro no mixer", e)
        } finally {
            track?.let { runCatching { it.stop(); it.release() } }
            _levels.value = Levels()
        }
    }

    // ------------------------------------------------------------------ mixer

    fun setMix(m: MixSettings, save: Boolean = true) {
        _mix.value = m
        mixer.musicVolume = m.musicVolume
        mixer.micVolume = m.micVolume
        mixer.duckLevel = m.duckLevel
        mixer.autoDuck = m.autoDuck
        mixer.duckAttackMs = m.duckAttackMs
        mixer.duckReleaseMs = m.duckReleaseMs
        monitorEnabled = m.monitor
        if (save) prefs.saveMix(m)
    }

    fun updateMix(block: (MixSettings) -> MixSettings) = setMix(block(_mix.value))

    // ------------------------------------------------------------------ microfone

    fun setMic(on: Boolean) {
        if (on) {
            startEngine()
            if (!mic.start()) {
                toast("Não foi possível abrir o microfone. Verifique a permissão.")
                return
            }
            mixer.micOn = true
            _micOn.value = true
        } else {
            mixer.micOn = false
            _micOn.value = false
            // espera o fade-out do microfone antes de desligar a captura
            main.postDelayed({ if (!mixer.micOn) mic.stop() }, 400)
        }
    }

    fun toggleMic() = setMic(!_micOn.value)

    // ------------------------------------------------------------------ player

    fun playIndex(i: Int) {
        val list = _player.value.playlist
        if (i !in list.indices) return
        startEngine()
        val t = list[i]
        currentToken = deck.load(Uri.parse(t.uri))
        musicPlaying = true
        _player.update { it.copy(index = i, playing = true, positionMs = 0, durationMs = 0) }
        broadcaster?.updateTitle(t.title)
    }

    fun togglePlay() {
        val p = _player.value
        if (p.index < 0 || p.current == null) {
            if (p.playlist.isNotEmpty()) playIndex(0) else toast("Adicione músicas na Playlist")
            return
        }
        musicPlaying = !musicPlaying
        _player.update { it.copy(playing = musicPlaying) }
    }

    fun next(auto: Boolean = false) {
        val p = _player.value
        if (p.playlist.isEmpty()) return
        val m = _mix.value
        val nextIndex = when {
            m.shuffle && p.playlist.size > 1 -> {
                var r: Int
                do { r = (p.playlist.indices).random() } while (r == p.index)
                r
            }
            p.index + 1 < p.playlist.size -> p.index + 1
            m.repeat || !auto -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            stopMusic()
        } else {
            playIndex(nextIndex)
        }
    }

    fun previous() {
        val p = _player.value
        if (p.playlist.isEmpty()) return
        if (deck.positionMs > 3000 || p.index <= 0) {
            if (p.index >= 0) playIndex(p.index) else playIndex(0)
        } else {
            playIndex(p.index - 1)
        }
    }

    fun stopMusic() {
        musicPlaying = false
        deck.stop()
        _player.update { it.copy(playing = false, positionMs = 0) }
    }

    fun seekTo(ms: Long) {
        if (_player.value.index < 0) return
        deck.seekTo(ms)
        _player.update { it.copy(positionMs = ms) }
    }

    override fun onTrackFinished(token: Int) {
        main.post { if (token == currentToken) next(auto = true) }
    }

    override fun onTrackError(token: Int, message: String) {
        main.post {
            if (token == currentToken) {
                toast("Erro: ${_player.value.current?.title ?: ""} - $message")
                next(auto = true)
            }
        }
    }

    override fun onDuration(token: Int, durationMs: Long) {
        main.post { if (token == currentToken) _player.update { it.copy(durationMs = durationMs) } }
    }

    // ------------------------------------------------------------------ playlist

    private fun setPlaylist(list: List<Track>, index: Int = _player.value.index) {
        _player.update { it.copy(playlist = list, index = index) }
        prefs.savePlaylist(list)
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val tracks = uris.map { Track(it.toString(), readTitle(it)) }
            main.post {
                setPlaylist(_player.value.playlist + tracks)
                toast("${tracks.size} música(s) adicionada(s)")
            }
        }
    }

    fun addFolder(tree: Uri) {
        scope.launch {
            val root = DocumentFile.fromTreeUri(app, tree) ?: return@launch
            val found = mutableListOf<Uri>()
            fun walk(dir: DocumentFile, depth: Int) {
                if (depth > 4) return
                dir.listFiles().sortedBy { it.name?.lowercase() }.forEach { f ->
                    if (f.isDirectory) walk(f, depth + 1)
                    else if (isAudio(f.type, f.name)) found += f.uri
                }
            }
            walk(root, 0)
            if (found.isEmpty()) toast("Nenhuma música encontrada na pasta")
            else addUris(found)
        }
    }

    private fun isAudio(mime: String?, name: String?): Boolean {
        if (mime?.startsWith("audio/") == true) return true
        val n = name?.lowercase() ?: return false
        return listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav").any { n.endsWith(it) }
    }

    private fun readTitle(uri: Uri): String {
        var name: String? = null
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        val fallback = (name ?: uri.lastPathSegment ?: "Música").substringBeforeLast('.')
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(app, uri)
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            when {
                !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$artist - $title"
                !title.isNullOrBlank() -> title
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun removeAt(i: Int) {
        val p = _player.value
        if (i !in p.playlist.indices) return
        val list = p.playlist.toMutableList().apply { removeAt(i) }
        when {
            i == p.index -> {
                stopMusic()
                setPlaylist(list, -1)
            }
            i < p.index -> setPlaylist(list, p.index - 1)
            else -> setPlaylist(list)
        }
    }

    fun move(from: Int, to: Int) {
        val p = _player.value
        if (from !in p.playlist.indices || to !in p.playlist.indices) return
        val list = p.playlist.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        val newIndex = when (p.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) p.index - 1 else p.index + 1
            else -> p.index
        }
        setPlaylist(list, newIndex)
    }

    fun clearPlaylist() {
        stopMusic()
        setPlaylist(emptyList(), -1)
    }

    // ------------------------------------------------------------------ transmissão

    fun saveConfig(c: StreamConfig) {
        _config.value = c
        prefs.saveConfig(c)
    }

    val isLive: Boolean get() = broadcaster != null

    fun goLive(): Boolean {
        val c = _config.value
        if (!c.isValid) {
            toast("Preencha servidor, porta e senha na aba Servidor")
            return false
        }
        if (broadcaster != null) return true
        startEngine()
        lateinit var b: Broadcaster
        b = Broadcaster(c, onStatus = { s ->
            if (broadcaster === b) {
                _live.value = s
                if (s.state == LiveState.ERROR) {
                    broadcaster = null
                    toast(s.message)
                }
            }
        })
        broadcaster = b
        _live.value = LiveStatus(LiveState.CONNECTING, "Conectando…")
        b.start()
        _player.value.current?.let { if (_player.value.playing) b.updateTitle(it.title) }
        return true
    }

    fun stopLive() {
        val b = broadcaster ?: return
        broadcaster = null
        b.stop()
        _live.value = LiveStatus(LiveState.OFF, "Desconectado", _live.value.bytesSent)
    }

    fun sendTitle(title: String) {
        broadcaster?.updateTitle(title)
    }
}
