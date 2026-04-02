package dev.davidv.translator.assistantOverlay

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

class TranslatorVoiceInteractionService : VoiceInteractionService() {
  private val tag = "TranslatorAssistant"

  override fun onReady() {
    super.onReady()
    Log.d(tag, "VoiceInteractionService ready")
  }

  override fun onPrepareToShowSession(
    args: Bundle,
    flags: Int,
  ) {
    super.onPrepareToShowSession(args, flags)
    Log.d(tag, "Preparing session with flags=$flags")
  }

  override fun onLaunchVoiceAssistFromKeyguard() {
    showSession(
      Bundle(),
      VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
    )
  }
}
