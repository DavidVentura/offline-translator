package dev.davidv.translator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val MIN_SPEECH_SPEED = 0.5f
private const val MAX_SPEECH_SPEED = 2.0f
private const val SPEECH_SPEED_STEP = 0.1f

@Composable
fun SpeechSpeedControl(
  speed: Float,
  onSpeedChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SpeechSpeedChip(
      label = "-",
      onClick = {
        onSpeedChange((speed - SPEECH_SPEED_STEP).coerceAtLeast(MIN_SPEECH_SPEED))
      },
    )
    Text(
      text = String.format(Locale.US, "%.2fx", speed),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )
    SpeechSpeedChip(
      label = "+",
      onClick = {
        onSpeedChange((speed + SPEECH_SPEED_STEP).coerceAtMost(MAX_SPEECH_SPEED))
      },
    )
  }
}

@Composable
private fun SpeechSpeedChip(
  label: String,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
