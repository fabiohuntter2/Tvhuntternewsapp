package com.tvhuntter.radiodj.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String = "${(value * 100).toInt()}%",
    onChangeFinished: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(valueText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onChangeFinished)
    }
}

/** Barra de VU horizontal (escala em dB, -48..0). */
@Composable
fun VuBar(label: String, peak: Float) {
    val db = if (peak <= 0.0001f) -60f else 20f * log10(peak)
    val frac = ((db + 48f) / 48f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Box(
            Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0A0F1E))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color(0xFF43A047), 0.7f to Color(0xFFFDD835), 1f to Color(0xFFE53935)
                        )
                    )
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "%d KB".format(b / 1000)
}
