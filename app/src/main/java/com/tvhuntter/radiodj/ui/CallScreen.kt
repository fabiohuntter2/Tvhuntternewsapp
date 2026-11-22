package com.tvhuntter.radiodj.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.call.Caller
import com.tvhuntter.radiodj.call.LineState
import kotlinx.coroutines.delay

private val CallGreen = Color(0xFF2E9E4F)

@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) { now = System.currentTimeMillis(); delay(1000) }
    }
    return now
}

@Composable
fun LineStatusChip(line: LineState) {
    val (text, color) = when (line) {
        LineState.READY -> "LINHA ABERTA" to CallGreen
        LineState.CONNECTING -> "ABRINDO LINHA" to Amber
        LineState.ERROR -> "LINHA COM FALHA" to Color(0xFF8E24AA)
        LineState.OFF -> "LINHA FECHADA" to Color(0xFF455A64)
    }
    Text(
        text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Faixa que aparece em qualquer aba quando há ouvinte chamando. */
@Composable
fun IncomingCallBanner(onOpen: () -> Unit) {
    val st by RadioEngine.callState.collectAsState()
    val first = st.waiting.firstOrNull() ?: return
    if (st.active != null) return
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(CallGreen)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.alpha(pulse))
        Text(
            "  ${first.name} está ligando" + if (st.waiting.size > 1) " (+${st.waiting.size - 1})" else "",
            color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = { RadioEngine.answerCall(first.id) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CallGreen),
        ) { Text("ATENDER", fontWeight = FontWeight.Black) }
    }
}

/** Painel de atendimento (Estúdio e aba Ouvintes). */
@Composable
fun CallPanel() {
    val st by RadioEngine.callState.collectAsState()
    val accept by RadioEngine.acceptCalls.collectAsState()
    val levels by RadioEngine.levels.collectAsState()
    val mix by RadioEngine.mix.collectAsState()
    if (!accept && st.active == null && st.waiting.isEmpty()) return
    val now = rememberNow(st.active != null || st.waiting.isNotEmpty())

    SectionCard("Ligações de ouvintes") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineStatusChip(st.line)
            Spacer(Modifier.size(8.dp))
            Text(st.lineDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }

        // ---- ouvinte atendido
        val a = st.active
        if (a != null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (st.onAir) Color(0xFF4A1416) else Color(0xFF15284D))
                    .border(2.dp, if (st.onAir) LiveRed else Color(0xFF2C4A8F), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (st.onAir) LiveRed else BrandBlue),
                        contentAlignment = Alignment.Center,
                    ) { Text(a.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(a.city.ifBlank { null }, formatTime(now - st.activeSince)).joinToString(" • "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when {
                            !st.connected -> "CONECTANDO…"
                            st.onAir -> "NO AR"
                            else -> "FORA DO AR"
                        },
                        fontWeight = FontWeight.Black, fontSize = 12.sp,
                        color = if (st.onAir) Color(0xFFFF8A80) else if (st.connected) Color(0xFF90CAF9) else Amber,
                    )
                }
                Spacer(Modifier.height(8.dp))
                VuBar("Ouvinte", levels.caller)
                Text(
                    if (st.onAir) "O ouvinte está no ar. A música abaixou automaticamente."
                    else "Conversa reservada: só vocês dois se ouvem. Toque em COLOCAR NO AR quando quiser.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { RadioEngine.setCallerOnAir(!st.onAir) },
                    enabled = st.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = if (st.onAir) Color(0xFF455A64) else LiveRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(if (st.onAir) "TIRAR DO AR" else "COLOCAR NO AR", fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                LabeledSlider(
                    "Volume do ouvinte", mix.callerVolume, { v -> RadioEngine.updateMix { it.copy(callerVolume = v) } },
                    range = 0f..2f,
                )
                OutlinedButton(
                    onClick = { RadioEngine.hangupCall(a.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CallEnd, null, tint = LiveRed)
                    Text("  Desligar", color = LiveRed, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.Headphones, null, modifier = Modifier.size(16.dp), tint = Amber)
                    Text(
                        "  Use fone de ouvido para o ouvinte não ouvir eco.",
                        fontSize = 12.sp, color = Amber,
                    )
                }
            }
        }

        // ---- fila
        if (st.waiting.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (a == null) "Chamando agora" else "Na fila (${st.waiting.size})",
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            st.waiting.forEach { c -> WaitingRow(c, now) }
        } else if (a == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (st.line == LineState.READY) "Nenhum ouvinte na linha. Divulgue o link na aba Ouvintes."
                else "A linha precisa estar aberta para receber ligações (requer internet).",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingRow(c: Caller, now: Long) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.city.ifBlank { null }, "esperando ${formatTime(now - c.since)}").joinToString(" • "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { RadioEngine.hangupCall(c.id) }) { Text("Recusar", color = LiveRed) }
        Button(
            onClick = { RadioEngine.answerCall(c.id) },
            colors = ButtonDefaults.buttonColors(containerColor = CallGreen),
        ) { Text("Atender", fontWeight = FontWeight.Bold) }
    }
}

/** Aba "Ouvintes": abrir/fechar a linha e divulgar o link. */
@Composable
fun ListenersScreen() {
    val context = LocalContext.current
    val accept by RadioEngine.acceptCalls.collectAsState()
    val code by RadioEngine.callCode.collectAsState()
    val config by RadioEngine.config.collectAsState()
    var confirmNew by remember { mutableStateOf(false) }
    val link = remember(code, config.stationName) { RadioEngine.callLink() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Linha de ouvintes") {
            SwitchRow("Receber ligações de ouvintes", accept) { RadioEngine.setAcceptCalls(it) }
            Text(
                "Com a linha aberta, qualquer pessoa com o link pode ligar para o estúdio pela internet. " +
                    "Você atende, conversa fora do ar e coloca no ar quando quiser.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CallPanel()

        SectionCard("Link para os ouvintes ligarem") {
            Text(
                link, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0F1E))
                    .padding(10.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "📻 Ligue ao vivo para a ${config.stationName}! Toque no link, diga seu nome e fale com o estúdio:\n$link",
                        )
                    }
                    context.startActivity(Intent.createChooser(send, "Compartilhar link"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null); Text(" Compartilhar")
                }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Link da rádio", link))
                    Toast.makeText(context, "Link copiado", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ContentCopy, null); Text(" Copiar")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Coloque esse link na bio do Instagram, no YouTube, no site ou mande no WhatsApp. " +
                    "O link é fixo: continua o mesmo nas próximas vezes.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirmNew = true }) { Text("Gerar um link novo") }
        }

        SectionCard("Como funciona") {
            listOf(
                "1. O ouvinte abre o link, digita o nome e toca em \"Ligar para a rádio\".",
                "2. O app avisa: \"Ouvinte chamando\". Toque em ATENDER.",
                "3. Vocês conversam fora do ar (a rádio não ouve).",
                "4. Toque em COLOCAR NO AR: a voz dele entra e a música abaixa sozinha.",
                "5. Toque em DESLIGAR para encerrar. Outros ouvintes ficam na fila.",
            ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Gerar link novo?") },
            text = { Text("O link atual deixa de funcionar. Use isso só se o link estiver sendo usado de forma indevida.") },
            confirmButton = { TextButton(onClick = { confirmNew = false; RadioEngine.regenerateCallLink() }) { Text("Gerar") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancelar") } },
        )
    }
}
