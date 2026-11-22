package com.tvhuntter.radiodj.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.core.LiveState
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(onOpenServer: () -> Unit, onOpenPlaylist: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveCard(onOpenServer)
        PlayerCard(onOpenPlaylist)
        MicCard()
        VolumeCard()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LiveCard(onOpenServer: () -> Unit) {
    val live by RadioEngine.live.collectAsState()
    val config by RadioEngine.config.collectAsState()
    val isOn = live.state != LiveState.OFF && live.state != LiveState.ERROR
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.state) {
        while (live.state == LiveState.LIVE) { now = System.currentTimeMillis(); delay(1000) }
    }

    SectionCard("Transmissão") {
        Text(
            "${config.cleanHost().ifBlank { "Servidor não configurado" }} • porta ${config.port}",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { if (isOn) RadioEngine.stopLive() else if (!RadioEngine.goLive()) onOpenServer() },
            colors = ButtonDefaults.buttonColors(containerColor = if (isOn) LiveRed else OkGreen),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (isOn) "SAIR DO AR" else "ENTRAR NO AR", fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        val color = when (live.state) {
            LiveState.LIVE -> Color(0xFFFF6E6E)
            LiveState.ERROR -> Color(0xFFCE93D8)
            LiveState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> Amber
        }
        Text(live.message, color = color, fontWeight = FontWeight.SemiBold)
        if (live.state == LiveState.LIVE) {
            Text(
                "Tempo no ar ${formatTime(now - live.liveSinceMs)} • enviado ${formatBytes(live.bytesSent)} • ${config.bitrateKbps} kbps",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (live.reconnects > 0) {
            Text("Reconexões: ${live.reconnects}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (live.state == LiveState.ERROR) {
            TextButton(onClick = onOpenServer) { Text("Revisar dados do servidor") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerCard(onOpenPlaylist: () -> Unit) {
    val p by RadioEngine.player.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    SectionCard("Tocando agora") {
        Text(
            p.current?.title ?: "Nenhuma música selecionada",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
        val next = p.playlist.getOrNull(p.index + 1)
        if (next != null) {
            Text("Próxima: ${next.title}", fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val dur = p.durationMs.coerceAtLeast(1)
        val pos = if (dragging) dragValue else (p.positionMs.toFloat() / dur).coerceIn(0f, 1f)
        Slider(
            value = pos,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                RadioEngine.seekTo((dragValue * dur).toLong()); dragging = false
            },
            enabled = p.current != null && p.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((pos * dur).toLong()), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (p.durationMs > 0) formatTime(p.durationMs) else "--:--", fontSize = 12.sp)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { RadioEngine.stopMusic() }) { Icon(Icons.Filled.Stop, "Parar", Modifier.size(30.dp)) }
            IconButton(onClick = { RadioEngine.previous() }) { Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(36.dp)) }
            FilledIconButton(onClick = { RadioEngine.togglePlay() }, modifier = Modifier.size(68.dp)) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Tocar/Pausar", Modifier.size(40.dp))
            }
            IconButton(onClick = { RadioEngine.next() }) { Icon(Icons.Filled.SkipNext, "Próxima", Modifier.size(36.dp)) }
            Spacer(Modifier.size(48.dp))
        }
        if (p.playlist.isEmpty()) {
            TextButton(onClick = onOpenPlaylist) { Text("Adicionar músicas à playlist") }
        }
    }
}

@Composable
private fun MicCard() {
    val micOn by RadioEngine.micOn.collectAsState()
    val levels by RadioEngine.levels.collectAsState()
    val bg by animateColorAsState(if (micOn) LiveRed else Color(0xFF26324F), label = "mic")

    SectionCard("Microfone") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(4.dp, if (micOn) Color(0xFFFF8A80) else Color(0xFF3D4B70), CircleShape)
                    .clickable { RadioEngine.toggleMic() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff, null,
                        tint = Color.White, modifier = Modifier.size(56.dp),
                    )
                    Text(
                        if (micOn) "NO AR" else "MIC",
                        color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (micOn) "Microfone aberto — música abaixada automaticamente"
                else "Toque para falar. A música abaixa sozinha.",
                textAlign = TextAlign.Center, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        VuBar("Música", levels.music)
        VuBar("Mic", levels.mic)
        VuBar("Saída E", levels.outL)
        VuBar("Saída D", levels.outR)
    }
}

@Composable
private fun VolumeCard() {
    val mix by RadioEngine.mix.collectAsState()
    SectionCard("Volumes e ducking") {
        LabeledSlider("Volume da música", mix.musicVolume, { v -> RadioEngine.updateMix { it.copy(musicVolume = v) } })
        LabeledSlider(
            "Volume do microfone", mix.micVolume, { v -> RadioEngine.updateMix { it.copy(micVolume = v) } },
            range = 0f..2f,
        )
        LabeledSlider(
            "Música com o mic aberto", mix.duckLevel, { v -> RadioEngine.updateMix { it.copy(duckLevel = v) } },
            range = 0f..0.8f,
        )
        LabeledSlider(
            "Velocidade da descida", mix.duckAttackMs.toFloat(),
            { v -> RadioEngine.updateMix { it.copy(duckAttackMs = v.toInt()) } },
            range = 100f..2000f, valueText = "%.1f s".format(mix.duckAttackMs / 1000f),
        )
        LabeledSlider(
            "Velocidade da volta", mix.duckReleaseMs.toFloat(),
            { v -> RadioEngine.updateMix { it.copy(duckReleaseMs = v.toInt()) } },
            range = 100f..3000f, valueText = "%.1f s".format(mix.duckReleaseMs / 1000f),
        )
        SwitchRow("Abaixar música ao abrir o mic (talkover)", mix.autoDuck) { v -> RadioEngine.updateMix { it.copy(autoDuck = v) } }
        SwitchRow("Ouvir a música no celular (retorno)", mix.monitor) { v -> RadioEngine.updateMix { it.copy(monitor = v) } }
        Text(
            "Dica: use fone de ouvido. O retorno toca só a música (sem a sua voz), para não dar microfonia.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
