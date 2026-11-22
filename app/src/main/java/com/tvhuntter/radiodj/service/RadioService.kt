package com.tvhuntter.radiodj.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tvhuntter.radiodj.R
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.core.LiveState
import com.tvhuntter.radiodj.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano: mantém música, microfone e transmissão funcionando
 * com a tela apagada ou o app minimizado.
 */
class RadioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(buildNotification("Estúdio pronto", "Toque para abrir"))
        RadioEngine.startEngine()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVHuntterDJ:radio").apply {
                setReferenceCounted(false); acquire()
            }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVHuntterDJ:wifi").apply {
                setReferenceCounted(false); acquire()
            }

        scope.launch {
            combine(RadioEngine.live, RadioEngine.player, RadioEngine.micOn) { live, player, mic ->
                val title = when (live.state) {
                    LiveState.LIVE -> if (mic) "🔴 NO AR • MICROFONE ABERTO" else "🔴 NO AR"
                    LiveState.CONNECTING, LiveState.RECONNECTING -> "Conectando ao servidor…"
                    LiveState.ERROR -> "Erro: ${live.message}"
                    LiveState.OFF -> if (mic) "Microfone aberto" else "Estúdio pronto"
                }
                val text = player.current?.let { (if (player.playing) "▶ " else "⏸ ") + it.title } ?: "Nenhuma música tocando"
                title to text
            }.distinctUntilChanged().collect { (t, x) ->
                val nm = getSystemService(NotificationManager::class.java)
                runCatching { nm.notify(NOTIF_ID, buildNotification(t, x)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LIVE -> RadioEngine.stopLive()
            ACTION_TOGGLE_MIC -> RadioEngine.toggleMic()
            ACTION_EXIT -> {
                RadioEngine.shutdown()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_TYPE -> goForeground(buildNotification("Estúdio pronto", "Toque para abrir"))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            // fallback sem o tipo microfone
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Transmissão ao vivo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Status da rádio ao vivo"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pending(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req, Intent(this, RadioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (RadioEngine.micOn.value) "Fechar mic" else "Abrir mic", pending(ACTION_TOGGLE_MIC, 1))
        if (RadioEngine.isLive) b.addAction(0, "Sair do ar", pending(ACTION_STOP_LIVE, 2))
        b.addAction(0, "Encerrar", pending(ACTION_EXIT, 3))
        return b.build()
    }

    companion object {
        private const val CHANNEL = "radio_live"
        private const val NOTIF_ID = 42
        const val ACTION_STOP_LIVE = "stop_live"
        const val ACTION_TOGGLE_MIC = "toggle_mic"
        const val ACTION_EXIT = "exit"
        const val ACTION_REFRESH_TYPE = "refresh_type"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, RadioService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(context, i)
        }
    }
}
