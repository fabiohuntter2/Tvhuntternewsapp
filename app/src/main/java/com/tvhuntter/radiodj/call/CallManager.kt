package com.tvhuntter.radiodj.call

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.tvhuntter.radiodj.core.PcmRingBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

enum class LineState { OFF, CONNECTING, READY, ERROR }

data class Caller(val id: String, val name: String, val city: String, val since: Long = System.currentTimeMillis())

data class CallUiState(
    val line: LineState = LineState.OFF,
    val lineDetail: String = "",
    /** Ouvintes chamando / na fila. */
    val waiting: List<Caller> = emptyList(),
    /** Ouvinte atendido. */
    val active: Caller? = null,
    /** Áudio do ouvinte já chegando. */
    val connected: Boolean = false,
    val onAir: Boolean = false,
    val activeSince: Long = 0,
)

/**
 * Central de ligações de ouvintes pela internet (WebRTC).
 * Uma página escondida (WebView) cuida da chamada; o áudio entra e sai por uma ponte
 * PCM com o mixer do app.
 */
class CallManager(
    private val context: Context,
    private val callerRing: PcmRingBuffer,
    private val events: Events,
) {
    interface Events {
        fun onRinging(caller: Caller)
        fun onActiveChanged(active: Boolean)
        fun onMessage(text: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageReady = false
    private var peerId: String? = null
    private var wantOpen = false

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    /** Lido pelo mixer: há ouvinte atendido com áudio. */
    @Volatile var audioActive = false
        private set

    // retorno para o ouvinte: junta 2 blocos antes de mandar para a página
    private val feedLock = Any()
    private var feedBuf = ShortArray(4096)
    private var feedLen = 0

    // ------------------------------------------------------------------ linha

    fun open(id: String) = main.post {
        wantOpen = true
        if (webView != null && peerId == id) return@post
        peerId = id
        if (webView == null) createWebView() else if (pageReady) startJs()
    }

    fun close() = main.post {
        wantOpen = false
        js("stop()")
        val wv = webView
        webView = null
        pageReady = false
        main.postDelayed({ runCatching { wv?.destroy() } }, 1500)
        setInactive()
        _state.value = CallUiState(LineState.OFF)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.addJavascriptInterface(Bridge(), "Android")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                pageReady = true
                if (wantOpen) startJs()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView caiu, recriando")
                if (view === webView) {
                    webView = null
                    pageReady = false
                    setInactive()
                    runCatching { view.destroy() }
                    if (wantOpen) main.postDelayed({ if (wantOpen && webView == null) createWebView() }, 2000)
                }
                return true
            }
        }
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        pageReady = false
        _state.update { it.copy(line = LineState.CONNECTING, lineDetail = "Abrindo a linha…") }
        wv.loadUrl("https://appassets.androidplatform.net/assets/call/host.html")
    }

    private fun startJs() {
        val id = peerId ?: return
        js("start(${JSONObject.quote(id)}, '{}')")
    }

    private fun js(code: String) {
        val wv = webView ?: return
        if (!pageReady) return
        wv.evaluateJavascript(code, null)
    }

    // ------------------------------------------------------------------ ações

    fun answer(id: String) = main.post {
        val caller = _state.value.waiting.find { it.id == id } ?: return@post
        // atender outro encerra a chamada atual
        _state.value.active?.let { if (it.id != id) js("hangup(${JSONObject.quote(it.id)})") }
        _state.update {
            it.copy(
                waiting = it.waiting.filter { c -> c.id != id },
                active = caller, connected = false, onAir = false, activeSince = System.currentTimeMillis(),
            )
        }
        js("answer(${JSONObject.quote(id)})")
    }

    fun hangup(id: String) = main.post {
        js("hangup(${JSONObject.quote(id)})")
        removeCaller(id)
    }

    fun hangupActive() {
        _state.value.active?.let { hangup(it.id) }
    }

    fun setOnAir(on: Boolean) = main.post {
        val a = _state.value.active ?: return@post
        _state.update { it.copy(onAir = on) }
        js("sendState(${JSONObject.quote(a.id)}, '${if (on) "onair" else "private"}')")
    }

    private fun removeCaller(id: String) {
        val wasActive = _state.value.active?.id == id
        _state.update {
            if (it.active?.id == id) it.copy(active = null, connected = false, onAir = false, activeSince = 0)
            else it.copy(waiting = it.waiting.filter { c -> c.id != id })
        }
        if (wasActive) setInactive()
    }

    private fun setInactive() {
        if (audioActive) {
            audioActive = false
            callerRing.clear()
            events.onActiveChanged(false)
        }
    }

    // ------------------------------------------------------------------ áudio

    /** Chamado pelo mixer com o retorno (mono, 44.1 kHz) para o ouvinte. */
    fun feed(mono: ShortArray, frames: Int) {
        if (!audioActive) return
        var payload: String? = null
        synchronized(feedLock) {
            if (feedLen + frames > feedBuf.size) feedLen = 0
            System.arraycopy(mono, 0, feedBuf, feedLen, frames)
            feedLen += frames
            if (feedLen >= 2048) {
                val bytes = ByteArray(feedLen * 2)
                for (i in 0 until feedLen) {
                    val v = feedBuf[i].toInt()
                    bytes[i * 2] = (v and 0xff).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                }
                feedLen = 0
                payload = Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        payload?.let { p -> main.post { js("feed('$p')") } }
    }

    private fun onPcmFromCaller(b64: String) {
        if (!audioActive) return
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return }
        val n = bytes.size / 2
        val stereo = ShortArray(n * 2)
        for (i in 0 until n) {
            val v = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            stereo[i * 2] = v
            stereo[i * 2 + 1] = v
        }
        callerRing.writeOverwrite(stereo, 0, stereo.size)
    }

    // ------------------------------------------------------------------ ponte JS

    private inner class Bridge {
        @JavascriptInterface
        fun onLine(state: String, detail: String) = main.post {
            val ls = when (state) {
                "ready" -> LineState.READY
                "connecting" -> LineState.CONNECTING
                "error" -> LineState.ERROR
                else -> LineState.OFF
            }
            val text = when (ls) {
                LineState.READY -> "Linha aberta — aguardando ligações"
                LineState.CONNECTING -> detail.ifBlank { "Abrindo a linha…" }
                LineState.ERROR -> detail.ifBlank { "Falha na linha" } + " (tentando de novo)"
                LineState.OFF -> "Linha fechada"
            }
            _state.update { it.copy(line = ls, lineDetail = text) }
        }

        @JavascriptInterface
        fun onIncoming(id: String, name: String, city: String) = main.post {
            val c = Caller(id, name.ifBlank { "Ouvinte" }, city)
            _state.update { st ->
                if (st.active?.id == id) st
                else st.copy(waiting = st.waiting.filter { it.id != id } + c)
            }
            events.onRinging(c)
        }

        @JavascriptInterface
        fun onCallState(id: String, state: String, detail: String) = main.post {
            when (state) {
                "active" -> if (_state.value.active?.id == id) {
                    callerRing.clear()
                    synchronized(feedLock) { feedLen = 0 }
                    _state.update { it.copy(connected = true) }
                    if (!audioActive) {
                        audioActive = true
                        events.onActiveChanged(true)
                    }
                }
                "ended" -> {
                    val name = (_state.value.active?.takeIf { it.id == id } ?: _state.value.waiting.find { it.id == id })?.name
                    val known = name != null
                    removeCaller(id)
                    if (known && detail.isNotBlank()) events.onMessage("$name: $detail")
                }
            }
        }

        @JavascriptInterface
        fun onPcm(b64: String) = onPcmFromCaller(b64)
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
