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
  fun `separate paragraphs do not merge into one block`() {
    val fragments =
      listOf(
        StyledFragment("We recommend using donation platforms like", Rect(42, 1109, 890, 1158)),
        StyledFragment("Liberapay", Rect(42, 1172, 223, 1221), blue),
        StyledFragment("or", Rect(222, 1172, 282, 1221)),
        StyledFragment("Open Collective", Rect(281, 1172, 573, 1221), blue),
        StyledFragment(
          ". These free software, community-driven donation platforms, support low-fee and transparent donations.",
          Rect(42, 1172, 996, 1347),
        ),
        StyledFragment(
          "You can support F-Droid via monthly recurring donations or with one-time gifts.",
          Rect(42, 1403, 894, 1515),
        ),
        StyledFragment(
          "Monthly donations keep F-Droid sustainable. By setting up a recurring monthly donation, you create a dependable funding stream.",
          Rect(42, 1571, 1028, 1872),
        ),
        StyledFragment(
          "The small gift of 5/month, when multiplied by thousands of supporters, results in a reliable funding stream.",
          Rect(42, 1928, 1023, 2276),
        ),
      )

    val blocks = clusterFragmentsIntoBlocks(fragments)
    assert(blocks.size >= 3) {
      "Expected separate paragraphs to be separate blocks, got ${blocks.size} block(s): ${blocks.map {
        "'${it.text.take(
          40,
        )}...' ${it.bounds.height()}px"
      }}"
    }
  }

  @Test
  fun `fragments in different groups do not merge even with vertical overlap`() {
    val webViewContent =
      listOf(
        StyledFragment("Article text here", Rect(42, 1842, 1012, 2088), group = 0),
        StyledFragment("Quick facts", Rect(73, 2185, 277, 2233), group = 0),
      )
    val nativeToolbar =
      listOf(
        StyledFragment("Save", Rect(0, 2085, 216, 2274), group = 1),
        StyledFragment("Language", Rect(216, 2085, 432, 2274), group = 1),
        StyledFragment("Contents", Rect(864, 2085, 1080, 2274), group = 1),
      )

    val blocks = clusterFragmentsIntoBlocks(webViewContent + nativeToolbar)

    val webBlocks = blocks.filter { it.text.contains("Article") || it.text.contains("Quick") }
    val toolbarBlocks = blocks.filter { it.text.contains("Save") || it.text.contains("Language") }

    assert(webBlocks.none { wb -> toolbarBlocks.any { tb -> tb.text in wb.text } }) {
      "WebView content and native toolbar should be in separate blocks"
    }
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
