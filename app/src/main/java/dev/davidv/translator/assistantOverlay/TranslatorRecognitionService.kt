package dev.davidv.translator.assistantOverlay

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class TranslatorRecognitionService : RecognitionService() {
  override fun onStartListening(
    recognizerIntent: Intent,
    listener: Callback,
  ) {
    listener.error(SpeechRecognizer.ERROR_CLIENT)
  }

  override fun onStopListening(listener: Callback) {
    listener.error(SpeechRecognizer.ERROR_CLIENT)
  }

  override fun onCancel(listener: Callback) {
  }
}
