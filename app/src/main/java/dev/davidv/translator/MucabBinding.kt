package dev.davidv.translator

import android.util.Log
import kotlin.system.measureTimeMillis

class MucabBinding {
  companion object {
    init {
      System.loadLibrary("bindings")
    }
  }

  private var dictPtr: Long = 0
  private val lock = Any()

  fun open(path: String): Boolean {
    synchronized(lock) {
      close()
      dictPtr = nativeOpen(path)
      return dictPtr != 0L
    }
  }

  fun transliterateJP(
    text: String,
    spaced: Boolean,
  ): String? =
    synchronized(lock) {
      if (dictPtr != 0L) {
        val result: String?
        val elapsed =
          measureTimeMillis {
            result = nativeTransliterateJP(dictPtr, text, spaced)
          }
        Log.d("MucabBindings", "Mucab transliteration took ${elapsed}ms")
        result
      } else {
        null
      }
    }

  fun close() {
    synchronized(lock) {
      if (dictPtr != 0L) {
        nativeClose(dictPtr)
        dictPtr = 0
      }
    }
  }

  fun isOpen(): Boolean = dictPtr != 0L

  internal fun dictionaryHandle(): Long =
    synchronized(lock) {
      dictPtr
    }

  private external fun nativeOpen(path: String): Long

  private external fun nativeTransliterateJP(
    dictPtr: Long,
    text: String,
    spaced: Boolean,
  ): String?

  private external fun nativeClose(dictPtr: Long)

  protected fun finalize() {
    close()
  }
}
