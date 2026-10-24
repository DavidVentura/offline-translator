/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import dev.davidv.translator.Language
import dev.davidv.translator.R
import kotlin.math.roundToInt

/**
 * The main-screen image surface: the image scaled to fill the available width (scaling small
 * images up), capped at `maxHeight`, and sized to the image so the content below sits right under
 * it. Pinch zooms (clamped) and, when zoomed, a one-finger drag pans; the word-selection overlay
 * is drawn directly on it and a tap selects. While OCR runs it shows the scan animation.
 * `showOriginal` (driven by the top-bar flip) swaps to the original image and its source words.
 */
@Composable
fun ImageDisplaySection(
  displayImage: Bitmap,
  originalImage: Bitmap?,
  showOriginal: Boolean,
  isProcessing: Boolean,
  detectedRegions: DetectedRegions?,
  wordSelection: ImageWordSelection?,
  maxHeight: Dp,
  onDictionaryLookup: ((String, Language) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val shown = if (showOriginal && originalImage != null) originalImage else displayImage
  val aspect = displayImage.width.toFloat() / displayImage.height.toFloat()
  val density = LocalDensity.current

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val heightForWidth = maxWidth / aspect
    val dispW = if (heightForWidth <= maxHeight) maxWidth else maxHeight * aspect
    val dispH = if (heightForWidth <= maxHeight) heightForWidth else maxHeight
    val dispWpx = with(density) { dispW.toPx() }
    val dispHpx = with(density) { dispH.toPx() }

    var scale by remember(displayImage) { mutableStateOf(1f) }
    var offset by remember(displayImage) { mutableStateOf(Offset.Zero) }
    val transformState =
      rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        offset =
          if (newScale <= 1f) {
            Offset.Zero
          } else {
            val maxX = dispWpx * (newScale - 1f) / 2f
            val maxY = dispHpx * (newScale - 1f) / 2f
            val proposed = offset + panChange * newScale
            Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
          }
        scale = newScale
      }

    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .size(dispW, dispH)
          .clip(RectangleShape)
          .absoluteOffset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
          .graphicsLayer(scaleX = scale, scaleY = scale)
          .transformable(transformState),
    ) {
      Image(
        bitmap = shown.asImageBitmap(),
        contentDescription =
          if (showOriginal) {
            stringResource(
              R.string.a11y_original_image,
            )
          } else {
            stringResource(R.string.a11y_translated_image)
          },
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )

      if (isProcessing && detectedRegions != null) {
        ScanAnimationOverlay(
          regions = detectedRegions,
          modifier = Modifier.matchParentSize(),
        )
      } else if (wordSelection != null) {
        val layer = if (showOriginal) wordSelection.source else wordSelection.translated
        WordSelectionOverlay(
          words = layer.words,
          imageWidth = wordSelection.imageWidth,
          imageHeight = wordSelection.imageHeight,
          scale = scale,
          onDictionaryLookup = onDictionaryLookup?.let { lookup -> { word -> lookup(word, layer.language) } },
          modifier = Modifier.matchParentSize(),
        )
      }
    }
  }
}
