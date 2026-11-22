package com.tvhuntter.radiodj.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvhuntter.radiodj.audio.RadioEngine

@Composable
fun PlaylistScreen() {
    val context = LocalContext.current
    val p by RadioEngine.player.collectAsState()
    val mix by RadioEngine.mix.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::persist)
        RadioEngine.addUris(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            RadioEngine.addFolder(uri)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { pickFiles.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.LibraryAdd, null); Text(" Músicas")
            }
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CreateNewFolder, null); Text(" Pasta")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            FilterChip(
                selected = mix.repeat, onClick = { RadioEngine.updateMix { it.copy(repeat = !it.repeat) } },
                label = { Text("Repetir") }, leadingIcon = { Icon(Icons.Filled.Repeat, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = mix.shuffle, onClick = { RadioEngine.updateMix { it.copy(shuffle = !it.shuffle) } },
                label = { Text("Aleatório") }, leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)) },
            )
            Text("${p.playlist.size} músicas", fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmClear = true }, enabled = p.playlist.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, "Limpar playlist")
            }
        }

        if (p.playlist.isEmpty()) {
            Text(
                "Sua playlist está vazia.\nToque em \"Músicas\" para escolher arquivos ou em \"Pasta\" para adicionar uma pasta inteira (MP3, M4A, AAC, OGG, FLAC, WAV).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            itemsIndexed(p.playlist) { i, t ->
                val current = i == p.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (current) Color(0xFF1E3A6E) else MaterialTheme.colorScheme.surface)
                        .clickable { RadioEngine.playIndex(i) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current && p.playing) {
                        Icon(Icons.Filled.Equalizer, null, tint = LiveRed, modifier = Modifier.width(28.dp))
                    } else {
                        Text("${i + 1}", fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    }
                    Text(
                        t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    if (i > 0) {
                        IconButton(onClick = { RadioEngine.move(i, i - 1) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Subir")
                        }
                    }
                    IconButton(onClick = { RadioEngine.removeAt(i) }) {
                        Icon(Icons.Filled.Delete, "Remover", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar playlist?") },
            text = { Text("Todas as músicas serão removidas da lista (os arquivos não são apagados).") },
            confirmButton = { TextButton(onClick = { confirmClear = false; RadioEngine.clearPlaylist() }) { Text("Limpar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}
