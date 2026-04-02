package dev.davidv.translator.assistantOverlay

import android.app.assist.AssistStructure
import android.graphics.Rect
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View

class AssistStructureLogger(
  private val tag: String,
  private val isDebuggable: Boolean,
  private val parser: AssistStructureParser,
) {
  fun log(
    state: VoiceInteractionSession.AssistState,
    structure: AssistStructure,
  ) {
    if (!isDebuggable) return
    Log.d(tag, "AssistStructure index=${state.index}/${state.count} windows=${structure.windowNodeCount}")
    for (windowIndex in 0 until structure.windowNodeCount) {
      val window = structure.getWindowNodeAt(windowIndex)
      Log.d(
        tag,
        "Window[$windowIndex] left=${window.left} top=${window.top} width=${window.width} height=${window.height} title=${window.title}",
      )
      logViewNode(window.rootViewNode, window.left, window.top, depth = 0)
    }

    val parsedBlocks = parser.parse(structure)
    Log.d(tag, "Parsed text blocks: ${parsedBlocks.size}")
    parsedBlocks.forEachIndexed { index, block ->
      Log.d(
        tag,
        "Parsed[$index] text=${block.text} bounds=${block.bounds.toShortString()} size=${block.style?.textSizePx} color=${block.style?.textColor} bg=${block.style?.textBackgroundColor} style=${block.style?.styleBits}",
      )
    }
  }

  private fun logViewNode(
    node: AssistStructure.ViewNode,
    baseLeft: Int,
    baseTop: Int,
    depth: Int,
  ) {
    val indent = "  ".repeat(depth)
    val left = baseLeft + node.left - node.scrollX
    val top = baseTop + node.top - node.scrollY
    val bounds = Rect(left, top, left + node.width, top + node.height)
    val line =
      buildString {
        append(indent)
        append("node class=")
        append(node.className)
        append(" id=")
        append(node.idEntry)
        append(" text=")
        append(node.text)
        append(" hint=")
        append(node.hint)
        append(" desc=")
        append(node.contentDescription)
        append(" bounds=")
        append(bounds.toShortString())
        append(" size=")
        append(node.textSize)
        append(" color=")
        append(node.textColor)
        append(" bg=")
        append(node.textBackgroundColor)
        append(" style=")
        append(node.textStyle)
        append(" baselines=")
        append(node.textLineBaselines?.contentToString())
        append(" charOffsets=")
        append(node.textLineCharOffsets?.contentToString())
        append(" scroll=(")
        append(node.scrollX)
        append(",")
        append(node.scrollY)
        append(")")
        append(" transform=")
        append(node.transformation)
        append(" alpha=")
        append(node.alpha)
        append(" visibility=")
        append(visibilityName(node.visibility))
      }
    Log.d(tag, line)

    for (childIndex in 0 until node.childCount) {
      val child = node.getChildAt(childIndex) ?: continue
      logViewNode(child, left, top, depth + 1)
    }
  }

  private fun visibilityName(value: Int): String =
    when (value) {
      View.VISIBLE -> "VISIBLE"
      View.INVISIBLE -> "INVISIBLE"
      View.GONE -> "GONE"
      else -> value.toString()
    }
}
