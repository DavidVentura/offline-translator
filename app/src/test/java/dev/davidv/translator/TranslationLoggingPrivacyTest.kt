package dev.davidv.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranslationLoggingPrivacyTest {
  @Test
  fun `exported Binder diagnostics accept only literal event messages`() {
    val source = File("src/main/java/dev/davidv/translator/AidlTranslationService.kt").readText()
    val calls = source.lineSequence().filter { it.contains("Log.") }.toList()
    assertTrue("The lifecycle diagnostics still provide useful events", calls.isNotEmpty())
    val literalEvent = Regex("""\s*Log\.[vdiew]\(tag, "[^"$]*"\)\s*""")
    calls.forEach { call ->
      assertTrue("Binder diagnostics must not expose request values or error payloads", literalEvent.matches(call))
    }
  }

  @Test
  fun `automatic detection and process text do not log message payloads`() {
    val detector = File("src/main/java/dev/davidv/translator/LanguageDetector.kt").readText()
    assertFalse("Detection receives the original private text", detector.contains("Log."))
    val processText = File("src/main/java/dev/davidv/translator/ProcessTextActivity.kt").readText()
    val calls = processText.lineSequence().filter { it.contains("Log.") }.toList()
    calls.forEach { call ->
      assertFalse("PROCESS_TEXT diagnostics must not interpolate translated text", call.contains("\$text"))
    }
  }

  @Test
  fun `translation and transliteration failures log only literal events`() {
    val source = File("src/main/java/dev/davidv/translator/TranslationService.kt").readText()
    val calls = source.lineSequence().filter { it.contains("Log.") }.toList()
    val literalEvent = Regex("""\s*Log\.[vdiew]\("TranslationService", "[^"$]*"\)\s*""")
    assertTrue(calls.isNotEmpty())
    calls.forEach { call ->
      assertTrue("Native exception payloads can contain private text", literalEvent.matches(call))
    }
  }

  @Test
  fun `UI diagnostics keep OCR speech and dictionary payloads private`() {
    val source = File("src/main/java/dev/davidv/translator/ui/TranslatorViewModel.kt").readText()
    val dispatch = source.substringAfter("fun handleMessage(").substringBefore("when (message)")
    assertTrue("Dispatch logs only the event type", dispatch.contains("message.javaClass.simpleName"))
    assertFalse("Event toString can contain extracted text or spoken output", dispatch.contains("\$message\""))
    assertFalse("Steering failures may carry a native payload", source.contains("Log.e(\"Steer\", r.message)"))
    val lookup = source.substringAfter("private fun handleDictionaryLookup(").substringBefore("private fun handleFileEvent(")
    lookup.lineSequence().filter { it.contains("Log.") }.forEach { call ->
      assertFalse("Dictionary diagnostics must not expose the requested word", call.contains("\$str"))
      assertFalse("Dictionary diagnostics must not expose the result", call.contains("\$foundWord"))
    }
  }
}
