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

package dev.davidv.translator

import dev.davidv.translator.SystemTtsSegment.Pause
import dev.davidv.translator.SystemTtsSegment.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsTextTest {
  private fun segments(input: String): List<SystemTtsSegment> {
    val parsed = parseSystemTtsText(input)
    assertTrue("expected segments, got $parsed", parsed is SystemTtsText.Segments)
    return (parsed as SystemTtsText.Segments).segments
  }

  @Test
  fun plainTextIsSpokenVerbatimEvenWithAngleBrackets() {
    assertEquals(listOf(Text("if a < b and <b>bold</b>")), segments("if a < b and <b>bold</b>"))
  }

  @Test
  fun inlineTagsAreDroppedKeepingTheirText() {
    assertEquals(
      listOf(Text("In 200 meters, turn left onto Main St")),
      segments(
        """<speak>In 200 meters, turn <emphasis level="strong">left</emphasis> onto """ +
          """<say-as interpret-as="address">Main St</say-as></speak>""",
      ),
    )
  }

  @Test
  fun namespacedDocumentWithXmlDeclarationIsParsed() {
    assertEquals(
      listOf(Text("Turn right")),
      segments(
        """<?xml version="1.0"?>
          |<speak version="1.1" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">Turn right</speak>
        """.trimMargin(),
      ),
    )
  }

  @Test
  fun breakSplitsTextWithItsDuration() {
    assertEquals(
      listOf(Text("Turn left"), Pause(300), Text("then right"), Pause(1500), Pause(700), Pause(400)),
      segments(
        """<speak>Turn left<break time="300ms"/>then right<break time="1.5s"/><break strength="strong"/><break/></speak>""",
      ),
    )
  }

  @Test
  fun subSpeaksAliasAndPhonemeSpeaksItsText() {
    assertEquals(
      listOf(Text("World Wide Web Consortium in tomato")),
      segments(
        """<speak><sub alias="World Wide Web Consortium">W3C</sub> in """ +
          """<phoneme alphabet="ipa" ph="təmeɪtoʊ">tomato</phoneme></speak>""",
      ),
    )
  }

  @Test
  fun audioSpeaksFallbackTextButNotDescription() {
    assertEquals(
      listOf(Text("ding")),
      segments("""<speak><audio src="https://example.com/d.mp3"><desc>a bell</desc>ding</audio></speak>"""),
    )
  }

  @Test
  fun paragraphsAndSentencesEndWithBlankLine() {
    assertEquals(listOf(Text("One\n\nTwo\n\n")), segments("<speak><p><s>One</s><s>Two</s></p></speak>"))
  }

  @Test
  fun entitiesAreDecoded() {
    assertEquals(listOf(Text("Fish & Chips")), segments("<speak>Fish &amp; Chips</speak>"))
  }

  @Test
  fun malformedSsmlIsRejected() {
    assertTrue(parseSystemTtsText("<speak>Turn <emphasis>left</speak>") is SystemTtsText.MalformedSsml)
    assertTrue(parseSystemTtsText("""<speak><break time="soon"/></speak>""") is SystemTtsText.MalformedSsml)
    assertTrue(parseSystemTtsText("""<speak><break strength="loud"/></speak>""") is SystemTtsText.MalformedSsml)
  }

  @Test
  fun externalEntitiesAreRejected() {
    val input =
      """<?xml version="1.0"?><!DOCTYPE speak [<!ENTITY x SYSTEM "file:///etc/hostname">]><speak>&x;</speak>"""
    assertTrue(parseSystemTtsText(input) is SystemTtsText.MalformedSsml)
  }

  @Test
  fun textStartingWithAngleBracketButNotSpeakRootIsVerbatim() {
    assertEquals(listOf(Text("<b>bold</b> text")), segments("<b>bold</b> text"))
    assertEquals(listOf(Text("< 5 minutes left")), segments("< 5 minutes left"))
  }

  @Test
  fun truncatedSpeakDocumentIsRejected() {
    assertTrue(parseSystemTtsText("<speak>Turn le") is SystemTtsText.MalformedSsml)
  }
}
