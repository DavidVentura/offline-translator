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

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Magnifier
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.davidv.translator.Point
import dev.davidv.translator.R
import uniffi.bindings.selectionNearestWord
import uniffi.bindings.selectionResolve
import uniffi.bindings.selectionWordAt
import uniffi.bindings.selectionWordAxis
import uniffi.translator_core.PositionedWord
import kotlin.math.max
import kotlin.math.min

/**
 * Per-word selection data for one translated image, in the image-pixel space of the displayed
 * bitmap. Source words come from the recognized text (CTC firings); translated words from the
 * overlay layout. Both produced in translator-rs; each word carries its `lineIndex`.
 */
data class ImageWordSelection(
  val imageWidth: Int,
  val imageHeight: Int,
  val sourceWords: List<PositionedWord>,
  val translatedWords: List<PositionedWord>,
)

/** Local-space distance from a point to an image-space position, for handle grabbing. */
private fun distanceTo(
  point: Offset,
  target: Point,
  t: FitTransform,
): Float = (point - Offset(t.mapX(target.x), t.mapY(target.y))).getDistance()

private fun copyToClipboard(
  context: Context,
  text: String,
) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("Selection", text))
}

private const val MENU_COPY = 1
private const val MENU_SHARE = 2
private const val MENU_SEARCH = 3

/**
 * A floating text-selection action bar (Copy / Share / Web search) over the image, mirroring the
 * system text toolbar. `textProvider`/`rectProvider` are read live so the bar follows the
 * selection. Returns a callback; the caller drives `startActionMode`.
 */
private class SelectionActionModeCallback(
  private val context: Context,
  private val textProvider: () -> String,
  private val rectProvider: () -> android.graphics.Rect,
  private val onFinished: () -> Unit,
) : ActionMode.Callback2() {
  override fun onCreateActionMode(
    mode: ActionMode,
    menu: Menu,
  ): Boolean {
    menu.add(0, MENU_COPY, 0, android.R.string.copy)
    menu.add(0, MENU_SHARE, 1, "Share")
    menu.add(0, MENU_SEARCH, 2, "Web search")
    return true
  }

  override fun onPrepareActionMode(
    mode: ActionMode,
    menu: Menu,
  ): Boolean = false

  override fun onActionItemClicked(
    mode: ActionMode,
    item: MenuItem,
  ): Boolean {
    val text = textProvider()
    when (item.itemId) {
      MENU_COPY -> copyToClipboard(context, text)
      MENU_SHARE -> {
        val intent =
          Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
          }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      MENU_SEARCH -> {
        val intent =
          Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        runCatching { context.startActivity(intent) }
      }
      else -> return false
    }
    mode.finish()
    return true
  }

  override fun onDestroyActionMode(mode: ActionMode) {
    onFinished()
  }

  override fun onGetContentRect(
    mode: ActionMode,
    view: View,
    outRect: android.graphics.Rect,
  ) {
    outRect.set(rectProvider())
  }
}

private enum class DragMode { START, END }

// A composable that holds a Magnifier gets unguarded casts to it (remember's result, the dispose
// lambda's capture) that fail to resolve below API 28, so only this class names the type.
@RequiresApi(Build.VERSION_CODES.P)
private class Loupe(view: View) {
  private val magnifier = Magnifier(view)

  // Raised a full bubble-height above the finger so it isn't occluded.
  fun showAbove(
    x: Float,
    y: Float,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      magnifier.show(x, y, x, y - magnifier.height.toFloat())
    } else {
      magnifier.show(x, y)
    }
  }

  fun dismiss() = magnifier.dismiss()
}

/**
 * Persistent Lens-style word selection over the displayed image. Tapping a word selects it (and
 * starts the selection); tapping another word moves the selection; tapping empty space clears it.
 * The selection stays with draggable end handles, and a floating action bar (Copy / Share / Web
 * search) tracks it. A plain drag is left for the container to pan/zoom; only a drag that grabs a
 * handle edits the selection. Nothing is auto-copied.
 *
 * `words` are in image-pixel space; mapped with the image's ContentScale.Fit transform, so this
 * stays aligned inside the image's transformed (zoom/pan) container. `scale` is the container's
 * current zoom — the magnifier is suppressed when zoomed (its source mapping assumes identity).
 */
@Composable
fun WordSelectionOverlay(
  words: List<PositionedWord>,
  imageWidth: Int,
  imageHeight: Int,
  scale: Float = 1f,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val view = LocalView.current
  val handlePainter = painterResource(R.drawable.selection_handle)
  // Loupe under the dragging finger so the text/handle isn't obscured. Native Magnifier is API
  // 28+; older devices simply get no loupe.
  val loupe = remember(view) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Loupe(view) else null }
  DisposableEffect(loupe) {
    onDispose { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loupe?.dismiss() }
  }

  var selStart by remember(words) { mutableStateOf<Int?>(null) }
  var selEnd by remember(words) { mutableStateOf<Int?>(null) }
  var dragging by remember { mutableStateOf(false) }
  var contentRect by remember { mutableStateOf(android.graphics.Rect()) }
  // The Canvas's layout coords; `localToRoot` maps Canvas-local points to root-view coords through
  // the container's zoom/pan transform, which the action bar and magnifier need.
  var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  val scaleState = rememberUpdatedState(scale)

  val hasSelection = selStart != null && selEnd != null
  // Pills, handles, bounds and copy text all come from translator-rs in one call, so this overlay
  // and the Qt one cannot disagree about what a drag selected.
  val selection =
    remember(words, selStart, selEnd) {
      val s = selStart
      val e = selEnd
      if (s != null && e != null) selectionResolve(words, s.toUInt(), e.toUInt()) else null
    }
  val selectedText = rememberUpdatedState(selection?.text ?: "")
  val rectState = rememberUpdatedState(contentRect)

  // Drive the floating action bar: present while there's a selection and no active drag.
  var actionMode by remember { mutableStateOf<ActionMode?>(null) }
  LaunchedEffect(hasSelection, dragging, selStart, selEnd) {
    if (hasSelection && !dragging) {
      if (actionMode == null) {
        actionMode =
          view.startActionMode(
            SelectionActionModeCallback(
              context = context,
              textProvider = { selectedText.value },
              rectProvider = { rectState.value },
              onFinished = { actionMode = null },
            ),
            ActionMode.TYPE_FLOATING,
          )
      } else {
        actionMode?.invalidateContentRect()
      }
    } else {
      actionMode?.finish()
      actionMode = null
    }
  }
  DisposableEffect(Unit) {
    onDispose { actionMode?.finish() }
  }

  Canvas(
    modifier =
      modifier
        .onGloballyPositioned { layoutCoords = it }
        .pointerInput(words, imageWidth, imageHeight) {
          val slop = viewConfiguration.touchSlop

          fun transform() = fitTransform(size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight)

          fun recomputeRect() {
            val s = selStart ?: return
            val e = selEnd ?: return
            val coords = layoutCoords ?: return
            val t = transform()
            val box = selectionResolve(words, s.toUInt(), e.toUInt())?.bounds ?: return
            val l = t.mapX(box.left.toFloat())
            val top = t.mapY(box.top.toFloat())
            val r = t.mapX(box.right.toFloat())
            val b = t.mapY(box.bottom.toFloat())
            // Map the local bounds through the container's zoom/pan to root-view coords.
            val topLeft = coords.localToRoot(Offset(l, top))
            val bottomRight = coords.localToRoot(Offset(r, b))
            contentRect =
              android.graphics.Rect(
                topLeft.x.toInt(),
                topLeft.y.toInt(),
                bottomRight.x.toInt(),
                bottomRight.y.toInt(),
              )
          }

          // The finger's root-view position comes through the container transform, so the loupe
          // tracks under zoom.
          fun showMagnifier(pos: Offset) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val l = loupe ?: return
            val root = (layoutCoords ?: return).localToRoot(pos)
            l.showAbove(root.x, root.y)
          }

          // Word whose oriented box (slightly padded) contains the touched point, or null.
          fun wordAt(pos: Offset): Int? {
            val t = transform()
            return selectionWordAt(words, t.unmapX(pos.x), t.unmapY(pos.y))?.toInt()
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val t = transform()
            // Grab radius in local coords; divide by zoom so it stays a constant on-screen size.
            val handleRadius = 28.dp.toPx() / scaleState.value
            val s0 = selStart
            val e0 = selEnd
            val view = if (s0 != null && e0 != null) selectionResolve(words, s0.toUInt(), e0.toUInt()) else null
            val handleMode =
              if (view != null) {
                when {
                  distanceTo(down.position, view.startHandle, t) < handleRadius -> DragMode.START
                  distanceTo(down.position, view.endHandle, t) < handleRadius -> DragMode.END
                  else -> null
                }
              } else {
                null
              }

            if (handleMode != null) {
              // Grab a handle: consume so the container doesn't pan, and resize the selection.
              down.consume()
              while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                dragging = true
                val fixed = if (handleMode == DragMode.START) e0!! else s0!!
                val tt = transform()
                val axis = selectionWordAxis(words, fixed.toUInt())
                selectionNearestWord(
                  words,
                  tt.unmapX(change.position.x),
                  tt.unmapY(change.position.y),
                  axis,
                )?.let { nw ->
                  selStart = min(fixed, nw.toInt())
                  selEnd = max(fixed, nw.toInt())
                }
                recomputeRect()
                showMagnifier(change.position)
              }
              dragging = false
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loupe?.dismiss()
            } else {
              // Not a handle: observe without consuming so the container can pan/zoom; a tap (no
              // drag) selects the word under the finger, or clears when it's on empty space.
              var moved = false
              while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!moved && (change.position - down.position).getDistance() > slop) moved = true
              }
              if (!moved) {
                val w = wordAt(down.position)
                selStart = w
                selEnd = w
                if (w != null) recomputeRect()
              }
            }
          }
        },
  ) {
    val view = selection ?: return@Canvas
    val t = fitTransform(size.width, size.height, imageWidth, imageHeight)
    // Pills arrive merged per line and already clamped against neighbouring lines.
    view.pills.forEach { pill -> drawOrientedPill(pill, t, Color(0x553B82F6)) }
    // End handles: pin markers whose tip sits at the line bottom, hanging below it. The opening
    // pin points up-right (toward the selection), the closing pin up-left. The drawable's tip is
    // at its bottom-centre, so anchor and rotate about that. Divide by zoom so the pin stays a
    // constant on-screen size instead of growing with the image.
    val handleSize = 26.dp.toPx() / scale

    fun drawHandle(
      p: Point,
      degrees: Float,
    ) {
      withTransform({
        translate(t.mapX(p.x) - handleSize / 2f, t.mapY(p.y) - handleSize)
        rotate(degrees, pivot = Offset(handleSize / 2f, handleSize))
      }) {
        with(handlePainter) { draw(Size(handleSize, handleSize)) }
      }
    }
    drawHandle(view.startHandle, -135f)
    drawHandle(view.endHandle, 135f)
  }
}
