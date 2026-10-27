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

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translator_align.DocumentDetection
import uniffi.translator_align.DocumentPoint
import uniffi.translator_align.DocumentQuad
import androidx.compose.ui.geometry.Size as GeometrySize

private const val TAG = "DocAlignActivity"
private const val HANDLE_HIT_RADIUS_PX = 80f
private const val HANDLE_DRAW_RADIUS_PX = 18f

class DocAlignActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )

    val inputUri: Uri =
      IntentCompat.getParcelableExtra(intent, EXTRA_INPUT_URI, Uri::class.java)
        ?: run {
          Log.e(TAG, "missing input URI")
          finishWithCancel()
          return
        }
    val outputUri: Uri =
      IntentCompat.getParcelableExtra(intent, EXTRA_OUTPUT_URI, Uri::class.java)
        ?: run {
          Log.e(TAG, "missing output URI")
          finishWithCancel()
          return
        }

    setContent {
      DocAlignScreen(
        inputUri = inputUri,
        onCancel = { finishWithCancel() },
        onSwitchToRect = { finishWithSwitch() },
        onConfirm = { bitmap, quad ->
          lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { warpAndWrite(bitmap, quad, outputUri) }
            if (ok) {
              setResult(Activity.RESULT_OK, Intent().setData(outputUri))
            } else {
              setResult(Activity.RESULT_CANCELED)
            }
            finish()
          }
        },
      )
    }
  }

  private fun warpAndWrite(
    bitmap: Bitmap,
    quad: DocumentQuad,
    outputUri: Uri,
  ): Boolean {
    val catalog = (application as TranslatorApplication).catalogRepository.catalog.value
    return try {
      val warped = catalog.warpDocumentRgba(bitmap, quad, postprocess = false)
      val out = Bitmap.createBitmap(warped.width.toInt(), warped.height.toInt(), Bitmap.Config.ARGB_8888)
      out.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(warped.rgba))
      contentResolver.openOutputStream(outputUri)?.use { stream ->
        out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
      } ?: run {
        Log.e(TAG, "openOutputStream returned null for $outputUri")
        return false
      }
      out.recycle()
      true
    } catch (e: Exception) {
      Log.e(TAG, "warp/write failed", e)
      false
    }
  }

  private fun finishWithCancel() {
    setResult(Activity.RESULT_CANCELED)
    finish()
  }

  private fun finishWithSwitch() {
    setResult(RESULT_SWITCH_TO_RECT, Intent())
    finish()
  }

  companion object {
    const val EXTRA_INPUT_URI = "dev.davidv.translator.DocAlign.InputUri"
    const val EXTRA_OUTPUT_URI = "dev.davidv.translator.DocAlign.OutputUri"
    const val RESULT_SWITCH_TO_RECT = Activity.RESULT_FIRST_USER + 1
  }
}

@Composable
private fun DocAlignScreen(
  inputUri: Uri,
  onCancel: () -> Unit,
  onSwitchToRect: () -> Unit,
  onConfirm: (Bitmap, DocumentQuad) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val catalog = (context.applicationContext as TranslatorApplication).catalogRepository.catalog.value

  var bitmap by remember(inputUri) { mutableStateOf<Bitmap?>(null) }
  var imageBmp by remember(inputUri) { mutableStateOf<ImageBitmap?>(null) }
  var imageSize by remember(inputUri) { mutableStateOf(IntSize.Zero) }
  var corners by remember(inputUri) { mutableStateOf<List<Offset>>(emptyList()) }
  var detecting by remember(inputUri) { mutableStateOf(true) }
  var redetecting by remember(inputUri) { mutableStateOf(false) }
  var errorMessage by remember(inputUri) { mutableStateOf<String?>(null) }
  var helpVisible by remember { mutableStateOf(false) }

  fun rotate90Clockwise() {
    val src = bitmap ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    val oldH = src.height.toFloat()
    val transformed = corners.map { Offset(oldH - it.y, it.x) }
    val reordered =
      if (transformed.size == 4) {
        listOf(transformed[3], transformed[0], transformed[1], transformed[2])
      } else {
        transformed
      }
    bitmap = rotated
    imageBmp = rotated.asImageBitmap()
    imageSize = IntSize(rotated.width, rotated.height)
    corners = reordered
  }

  val detectFn: suspend (Bitmap) -> DocumentDetection? =
    remember(catalog) {
      { bmp ->
        withContext(Dispatchers.Default) {
          runCatching { catalog.detectDocumentQuad(bmp) }
            .onFailure { Log.e(TAG, "detect failed", it) }
            .getOrNull()
        }
      }
    }

  LaunchedEffect(inputUri) {
    val loaded =
      withContext(Dispatchers.IO) {
        runCatching { decodeOrientedBitmap(context, inputUri) }.getOrNull()
      }
    if (loaded == null) {
      errorMessage = context.getString(R.string.docalign_decode_failed)
      detecting = false
      return@LaunchedEffect
    }
    bitmap = loaded
    imageBmp = loaded.asImageBitmap()
    imageSize = IntSize(loaded.width, loaded.height)

    val detected = detectFn(loaded)
    corners =
      if (detected != null) {
        quadToOffsets(detected.quad)
      } else {
        listOf(
          Offset(0f, 0f),
          Offset(loaded.width.toFloat(), 0f),
          Offset(loaded.width.toFloat(), loaded.height.toFloat()),
          Offset(0f, loaded.height.toFloat()),
        )
      }
    detecting = false
  }

  Box(modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
    Column(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        val bmp = imageBmp
        val raw = bitmap
        val img = imageSize
        if (errorMessage != null) {
          Text(errorMessage!!, color = Color.White)
        } else if (bmp == null || raw == null || img == IntSize.Zero || corners.isEmpty()) {
          CircularProgressIndicator(color = Color.White)
        } else {
          QuadEditorCanvas(
            image = bmp,
            originalBitmap = raw,
            imageSize = img,
            corners = corners,
            onCornersChange = { corners = it },
            detectQuad = detectFn,
            onDetectingChange = { redetecting = it },
          )
        }
      }

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BarTextButton(
          label = stringResource(R.string.common_cancel),
          enabled = true,
          onClick = onCancel,
          modifier = Modifier.weight(1f),
        )
        BarTextButton(
          label = stringResource(R.string.common_ok),
          enabled = !detecting && corners.size == 4 && bitmap != null,
          onClick = {
            val bmp = bitmap
            val quad = offsetsToQuad(corners)
            if (bmp != null && quad != null) onConfirm(bmp, quad)
          },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
      IconButton(onClick = { helpVisible = true }) {
        Icon(
          painter = painterResource(id = R.drawable.help_outline),
          contentDescription = stringResource(R.string.a11y_help),
          tint = Color.White,
        )
      }
      if (helpVisible) {
        Popup(
          alignment = Alignment.TopStart,
          offset = IntOffset(0, with(LocalDensity.current) { 48.dp.toPx().toInt() }),
          onDismissRequest = { helpVisible = false },
          properties = PopupProperties(focusable = true),
        ) {
          Box(
            modifier =
              Modifier
                .background(Color(0xCC222222))
                .padding(16.dp),
          ) {
            Text(
              text = stringResource(R.string.docalign_help),
              color = Color.White,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.widthIn(max = 300.dp),
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { rotate90Clockwise() }) {
        Icon(
          painter = painterResource(id = R.drawable.rotate_right),
          contentDescription = stringResource(R.string.a11y_rotate_90),
          tint = Color.White,
        )
      }
      IconButton(onClick = onSwitchToRect) {
        Icon(
          painter = painterResource(id = R.drawable.activity_zone),
          contentDescription = stringResource(R.string.a11y_switch_rectangular_crop),
          tint = Color.White,
        )
      }
    }

    if (detecting || redetecting) {
      Box(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
      ) {
        Text(
          text = if (detecting) stringResource(R.string.docalign_detecting) else stringResource(R.string.docalign_redetecting),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun QuadEditorCanvas(
  image: ImageBitmap,
  originalBitmap: Bitmap,
  imageSize: IntSize,
  corners: List<Offset>,
  onCornersChange: (List<Offset>) -> Unit,
  detectQuad: suspend (Bitmap) -> DocumentDetection?,
  onDetectingChange: (Boolean) -> Unit,
) {
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var viewScale by remember(imageSize) { mutableStateOf(1f) }
  var viewOffset by remember(imageSize) { mutableStateOf(Offset.Zero) }

  val currentCorners by rememberUpdatedState(corners)
  val currentOnChange by rememberUpdatedState(onCornersChange)
  val currentDetect by rememberUpdatedState(detectQuad)
  val currentOriginal by rememberUpdatedState(originalBitmap)
  val currentOnDetecting by rememberUpdatedState(onDetectingChange)
  val scope = rememberCoroutineScope()
  val touchSlopPx = with(LocalDensity.current) { 6.dp.toPx() }

  fun displayedRect(canvas: IntSize): DisplayRect {
    val base = computeFitCenter(canvas, imageSize)
    val w = base.size.width * viewScale
    val h = base.size.height * viewScale
    val center = Offset(canvas.width / 2f, canvas.height / 2f)
    val offset =
      Offset(
        center.x - w * 0.5f + viewOffset.x,
        center.y - h * 0.5f + viewOffset.y,
      )
    return DisplayRect(offset, GeometrySize(w, h))
  }

  // Re-run detection on the original-image region currently visible in the canvas. Maps the
  // returned corners back into original-image coordinates. The same trick as the PoC: the
  // model is more confident when the document fills most of its 256x256 input.
  suspend fun redetectVisible() {
    val cs = canvasSize
    if (cs == IntSize.Zero) return
    val display = displayedRect(cs)
    if (display.size.width <= 0f || display.size.height <= 0f) return
    val sX = imageSize.width / display.size.width
    val sY = imageSize.height / display.size.height
    val imgLeft = ((-display.offset.x) * sX).toInt().coerceIn(0, imageSize.width - 1)
    val imgTop = ((-display.offset.y) * sY).toInt().coerceIn(0, imageSize.height - 1)
    val imgRight = ((cs.width - display.offset.x) * sX).toInt().coerceIn(imgLeft + 1, imageSize.width)
    val imgBottom = ((cs.height - display.offset.y) * sY).toInt().coerceIn(imgTop + 1, imageSize.height)
    val w = imgRight - imgLeft
    val h = imgBottom - imgTop
    if (w < 64 || h < 64) return
    currentOnDetecting(true)
    val source = currentOriginal
    val cropped =
      withContext(Dispatchers.IO) {
        runCatching { Bitmap.createBitmap(source, imgLeft, imgTop, w, h) }.getOrNull()
      }
    if (cropped == null) {
      currentOnDetecting(false)
      return
    }
    val detection = runCatching { currentDetect(cropped) }.getOrNull()
    // createBitmap returns `source` unchanged when the crop covers the whole image (zoomed
    // out); recycling it would destroy the bitmap still being drawn on the canvas.
    if (cropped !== source) cropped.recycle()
    currentOnDetecting(false)
    if (detection != null) {
      val inOriginal =
        quadToOffsets(detection.quad).map {
          Offset(it.x + imgLeft, it.y + imgTop)
        }
      currentOnChange(inOriginal)
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .onSizeChanged { canvasSize = it }
        .pointerInput(canvasSize, imageSize) {
          if (canvasSize == IntSize.Zero) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPos = down.position

            val display = displayedRect(canvasSize)
            val screenHandles = currentCorners.map { it.imageToScreen(display, imageSize) }
            val closestEntry =
              screenHandles.withIndex()
                .map { (i, h) -> i to (h - downPos).getDistance() }
                .minByOrNull { it.second }
            val cornerIdx = closestEntry?.takeIf { it.second <= HANDLE_HIT_RADIUS_PX }?.first ?: -1

            var mode = GestureMode.Pending
            var totalMovement = Offset.Zero
            var lastCentroid = downPos
            var lastSpread = 0f
            var didView = false

            while (true) {
              val event = awaitPointerEvent()
              val pressed = event.changes.filter { it.pressed }
              if (pressed.isEmpty()) break

              if (pressed.size >= 2) {
                val centroid =
                  pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                    pressed.size.toFloat()
                val spread =
                  pressed
                    .map { (it.position - centroid).getDistance() }
                    .average().toFloat()
                if (mode == GestureMode.MultiTouch && lastSpread > 0f && spread > 0f) {
                  val scaleFactor = spread / lastSpread
                  val displayBefore = displayedRect(canvasSize)
                  val imgFocus = centroid.screenToImage(displayBefore, imageSize)
                  viewScale = (viewScale * scaleFactor).coerceIn(0.5f, 12f)
                  val displayAfter = displayedRect(canvasSize)
                  val newScreenFocus = imgFocus.imageToScreen(displayAfter, imageSize)
                  viewOffset = viewOffset + (centroid - newScreenFocus) +
                    (centroid - lastCentroid)
                  didView = true
                } else {
                  mode = GestureMode.MultiTouch
                }
                lastCentroid = centroid
                lastSpread = spread
                pressed.forEach { it.consume() }
                continue
              }

              if (mode == GestureMode.MultiTouch) {
                pressed.forEach { it.consume() }
                continue
              }

              val change = pressed[0]
              val drag = change.positionChange()
              totalMovement += drag

              if (cornerIdx >= 0 && mode != GestureMode.Pan) {
                if (drag != Offset.Zero) {
                  val d = displayedRect(canvasSize)
                  val scale = d.size.width / imageSize.width
                  val imgDelta = Offset(drag.x / scale, drag.y / scale)
                  val updated =
                    currentCorners.toMutableList().apply {
                      val cur = this[cornerIdx]
                      this[cornerIdx] =
                        Offset(
                          (cur.x + imgDelta.x).coerceIn(0f, imageSize.width.toFloat()),
                          (cur.y + imgDelta.y).coerceIn(0f, imageSize.height.toFloat()),
                        )
                    }
                  currentOnChange(updated)
                  mode = GestureMode.CornerDrag
                  change.consume()
                }
              } else if (cornerIdx < 0 && (totalMovement.getDistance() > touchSlopPx || mode == GestureMode.Pan)) {
                viewOffset += drag
                didView = true
                mode = GestureMode.Pan
                change.consume()
              }
            }

            if (didView) {
              scope.launch { redetectVisible() }
            }
          }
        },
  ) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
      val display = displayedRect(IntSize(size.width.toInt(), size.height.toInt()))
      drawImage(
        image = image,
        dstOffset = IntOffset(display.offset.x.toInt(), display.offset.y.toInt()),
        dstSize =
          IntSize(
            display.size.width.toInt().coerceAtLeast(1),
            display.size.height.toInt().coerceAtLeast(1),
          ),
      )
      val screenHandles = corners.map { it.imageToScreen(display, imageSize) }
      drawQuadOverlay(screenHandles)
    }
  }
}

private enum class GestureMode { Pending, CornerDrag, Pan, MultiTouch }

@Composable
private fun BarTextButton(
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .let { if (enabled) it.clickable(onClick = onClick) else it },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label.uppercase(),
      style = MaterialTheme.typography.labelLarge,
      color = color,
    )
  }
}

private data class DisplayRect(val offset: Offset, val size: GeometrySize)

private fun computeFitCenter(
  canvas: IntSize,
  image: IntSize,
): DisplayRect {
  if (canvas.width <= 0 || canvas.height <= 0) {
    return DisplayRect(Offset.Zero, GeometrySize.Zero)
  }
  val scale =
    minOf(
      canvas.width.toFloat() / image.width,
      canvas.height.toFloat() / image.height,
    )
  val w = image.width * scale
  val h = image.height * scale
  val ox = (canvas.width - w) * 0.5f
  val oy = (canvas.height - h) * 0.5f
  return DisplayRect(Offset(ox, oy), GeometrySize(w, h))
}

private fun Offset.imageToScreen(
  display: DisplayRect,
  image: IntSize,
): Offset {
  if (image.width == 0 || image.height == 0) return display.offset
  val scaleX = display.size.width / image.width
  val scaleY = display.size.height / image.height
  return Offset(display.offset.x + x * scaleX, display.offset.y + y * scaleY)
}

private fun Offset.screenToImage(
  display: DisplayRect,
  image: IntSize,
): Offset {
  if (display.size.width == 0f || display.size.height == 0f) return Offset.Zero
  val scaleX = image.width.toFloat() / display.size.width
  val scaleY = image.height.toFloat() / display.size.height
  return Offset((x - display.offset.x) * scaleX, (y - display.offset.y) * scaleY)
}

private fun DrawScope.drawQuadOverlay(screenCorners: List<Offset>) {
  if (screenCorners.size != 4) return
  val path =
    Path().apply {
      moveTo(screenCorners[0].x, screenCorners[0].y)
      lineTo(screenCorners[1].x, screenCorners[1].y)
      lineTo(screenCorners[2].x, screenCorners[2].y)
      lineTo(screenCorners[3].x, screenCorners[3].y)
      close()
    }
  drawPath(path, color = Color(0x6633CCFF), style = Stroke(width = 4f))
  screenCorners.forEach { c ->
    drawCircle(color = Color.White, radius = HANDLE_DRAW_RADIUS_PX, center = c)
    drawCircle(color = Color(0xFF1976D2), radius = HANDLE_DRAW_RADIUS_PX * 0.65f, center = c)
  }
}

private fun quadToOffsets(quad: DocumentQuad): List<Offset> =
  listOf(
    Offset(quad.topLeft.x, quad.topLeft.y),
    Offset(quad.topRight.x, quad.topRight.y),
    Offset(quad.bottomRight.x, quad.bottomRight.y),
    Offset(quad.bottomLeft.x, quad.bottomLeft.y),
  )

private fun offsetsToQuad(corners: List<Offset>): DocumentQuad? {
  if (corners.size != 4) return null
  return DocumentQuad(
    topLeft = DocumentPoint(corners[0].x, corners[0].y),
    topRight = DocumentPoint(corners[1].x, corners[1].y),
    bottomRight = DocumentPoint(corners[2].x, corners[2].y),
    bottomLeft = DocumentPoint(corners[3].x, corners[3].y),
  )
}

private fun decodeOrientedBitmap(
  context: android.content.Context,
  uri: Uri,
): Bitmap? {
  val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
  val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
  val orientation =
    try {
      java.io.ByteArrayInputStream(raw).use {
        ExifInterface(it).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      }
    } catch (_: Exception) {
      ExifInterface.ORIENTATION_NORMAL
    }
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
    else -> return convertToArgb8888(decoded)
  }
  val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
  if (rotated !== decoded) decoded.recycle()
  return convertToArgb8888(rotated)
}

private fun convertToArgb8888(src: Bitmap): Bitmap {
  if (src.config == Bitmap.Config.ARGB_8888) return src
  val converted = src.copy(Bitmap.Config.ARGB_8888, false)
  src.recycle()
  return converted
}
