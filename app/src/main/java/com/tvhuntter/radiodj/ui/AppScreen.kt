package com.tvhuntter.radiodj.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvhuntter.radiodj.R
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.core.LiveState

@Composable
fun AppScreen(onExit: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }
    val live by RadioEngine.live.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF060A16))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_header),
                    contentDescription = "TV Huntter News",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(48.dp).width(140.dp),
                )
                Spacer(Modifier.weight(1f))
                LiveBadge(live.state)
                IconButton(onClick = { confirmExit = true }) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Encerrar app")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF060A16)) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Radio, null) }, label = { Text("Estúdio") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, label = { Text("Playlist") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.SupportAgent, null) }, label = { Text("Ouvintes") },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Servidor") },
                )
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (tab != 0) IncomingCallBanner(onOpen = { tab = 0 })
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> StudioScreen(onOpenServer = { tab = 3 }, onOpenPlaylist = { tab = 1 })
                    1 -> PlaylistScreen()
                    2 -> ListenersScreen()
                    else -> ServerScreen()
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Encerrar o app?") },
            text = { Text("A transmissão, a música e o microfone serão desligados.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onExit() }) { Text("Encerrar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun LiveBadge(state: LiveState) {
    val (text, color) = when (state) {
        LiveState.LIVE -> "NO AR" to LiveRed
        LiveState.CONNECTING, LiveState.RECONNECTING -> "CONECTANDO" to Amber
        LiveState.ERROR -> "ERRO" to Color(0xFF8E24AA)
        LiveState.OFF -> "FORA DO AR" to Color(0xFF455A64)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
