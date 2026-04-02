package dev.davidv.translator

import org.junit.Assert.assertEquals
import org.junit.Test

class StyledTextPipelineTest {
  private val blue = TextStyle(textColor = 0xFF0000FF.toInt())

  @Test
  fun `preserves document order when wrapped link bbox starts higher than preceding text`() {
    val fragments =
      listOf(
        StyledFragment(
          "We recommend using donation platforms such as",
          Rect(10, 100, 300, 130),
        ),
        StyledFragment("Liberapay", Rect(10, 130, 100, 155), blue),
        StyledFragment(
          ". This free software, community-driven donation platforms, support transparent and low-fee donations. or",
          Rect(100, 130, 300, 220),
        ),
        StyledFragment("Open Collective", Rect(10, 200, 180, 250), blue),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(1, blocks.size)

    val text = blocks[0].text
    val liberapayPos = text.indexOf("Liberapay")
    val donationsPos = text.indexOf("donations. or")
    val openCollectivePos = text.indexOf("Open Collective")

    assert(liberapayPos < donationsPos) {
      "Liberapay should come before 'donations. or' but was at $liberapayPos vs $donationsPos"
    }
    assert(donationsPos < openCollectivePos) {
      "donations. or should come before Open Collective but was at $donationsPos vs $openCollectivePos"
    }
  }

  @Test
  fun `fragments on same visual line are grouped into one line`() {
    val fragments =
      listOf(
        StyledFragment("Hello", Rect(10, 100, 60, 130)),
        StyledFragment("world", Rect(65, 100, 120, 130)),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(1, blocks.size)
    assertEquals("Hello world", blocks[0].text)
  }

  @Test
  fun `fragments on different visual lines get newline separator`() {
    val fragments =
      listOf(
        StyledFragment("Line one", Rect(10, 100, 200, 130)),
        StyledFragment("Line two", Rect(10, 140, 200, 170)),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(1, blocks.size)
    assertEquals("Line one\nLine two", blocks[0].text)
  }

  @Test
  fun `spatially separate fragments become separate blocks`() {
    val fragments =
      listOf(
        StyledFragment("Top block", Rect(10, 10, 200, 40)),
        StyledFragment("Bottom block", Rect(10, 500, 200, 530)),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(2, blocks.size)
    assertEquals("Top block", blocks[0].text)
    assertEquals("Bottom block", blocks[1].text)
  }

  @Test
  fun `style spans track character positions correctly`() {
    val bold = TextStyle(bold = true)
    val fragments =
      listOf(
        StyledFragment("normal", Rect(10, 100, 80, 130)),
        StyledFragment("bold", Rect(85, 100, 130, 130), bold),
        StyledFragment("normal", Rect(135, 100, 200, 130)),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(1, blocks.size)
    assertEquals("normal bold normal", blocks[0].text)

    val boldSpan = blocks[0].styleSpans.single()
    assertEquals(7, boldSpan.start)
    assertEquals(11, boldSpan.end)
    assertEquals(bold, boldSpan.style)
  }

  @Test
  fun `overlapping multi-line bboxes merge into one block`() {
    val fragments =
      listOf(
        StyledFragment("first paragraph end.", Rect(10, 100, 300, 160)),
        StyledFragment("second paragraph start.", Rect(10, 140, 300, 200)),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assertEquals(1, blocks.size)
  }
}
