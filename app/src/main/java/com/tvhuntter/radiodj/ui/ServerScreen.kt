package com.tvhuntter.radiodj.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.core.ServerType

@Composable
fun ServerScreen() {
    val context = LocalContext.current
    val saved by RadioEngine.config.collectAsState()
    var type by remember(saved) { mutableStateOf(saved.type) }
    var host by remember(saved) { mutableStateOf(saved.host) }
    var port by remember(saved) { mutableStateOf(saved.port.toString()) }
    var user by remember(saved) { mutableStateOf(saved.user) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var mount by remember(saved) { mutableStateOf(saved.mount) }
    var bitrate by remember(saved) { mutableStateOf(saved.bitrateKbps) }
    var station by remember(saved) { mutableStateOf(saved.stationName) }
    var genre by remember(saved) { mutableStateOf(saved.genre) }
    var showPass by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Tipo de servidor") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
            }
            Text(
                if (type == ServerType.SHOUTCAST)
                    "Use para a PORTA DJ de painéis SHOUTcast (MXCast, Centova, SonicPanel). Se o painel mostrar usuário e senha de DJ, preencha os dois."
                else "Use para Icecast, AzuraCast ou Liquidsoap (precisa do ponto de montagem).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Dados de conexão") {
            OutlinedTextField(
                host, { host = it }, label = { Text("Servidor / IP (ex.: stm1.mxcast.com.br)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) },
                label = { Text(if (type == ServerType.SHOUTCAST) "Porta DJ" else "Porta") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (type == ServerType.ICECAST) {
                OutlinedTextField(
                    mount, { mount = it }, label = { Text("Ponto de montagem (ex.: /live)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                user, { user = it },
                label = { Text(if (type == ServerType.SHOUTCAST) "Usuário DJ (se houver)" else "Usuário (padrão: source)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Senha") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Mostrar senha")
                    }
                },
            )
        }

        SectionCard("Qualidade (MP3)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64, 96, 128, 192).forEach { b ->
                    FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text("$b kbps") })
                }
            }
            Text(
                "128 kbps é o padrão das rádios. Em internet móvel fraca, use 64 ou 96. Não passe do limite do seu plano de streaming.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Rádio") {
            OutlinedTextField(station, { station = it }, label = { Text("Nome da rádio") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(genre, { genre = it }, label = { Text("Gênero") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = {
                RadioEngine.saveConfig(
                    saved.copy(
                        type = type, host = host.trim(), port = port.toIntOrNull() ?: 0,
                        user = user.trim(), password = password, mount = mount.trim(),
                        bitrateKbps = bitrate, stationName = station, genre = genre,
                    )
                )
                val msg = if (RadioEngine.isLive) "Salvo. Saia e entre no ar de novo para aplicar." else "Configuração salva"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("SALVAR") }
    }
}
