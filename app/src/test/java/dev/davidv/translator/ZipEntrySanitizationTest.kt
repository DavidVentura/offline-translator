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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ZipEntrySanitizationTest {
  @get:Rule
  val temp = TemporaryFolder()

  private fun extractTo(): File = temp.newFolder("models")

  private fun assertContained(
    root: File,
    target: File?,
  ) {
    assertNotNull("expected a resolved target", target)
    val canonicalRoot = root.canonicalPath
    val canonicalTarget = target!!.canonicalPath
    assertTrue(
      "expected $canonicalTarget to stay under $canonicalRoot",
      canonicalTarget == canonicalRoot || canonicalTarget.startsWith(canonicalRoot + File.separator),
    )
  }

  // --- normalizeZipEntryName: pure naming transform -----------------------

  @Test
  fun `normalize strips leading slash and dot-slash`() {
    assertEquals("a/b.bin", normalizeZipEntryName("/a/b.bin", null))
    assertEquals("a/b.bin", normalizeZipEntryName("./a/b.bin", null))
  }

  @Test
  fun `normalize re-roots entries under the install root`() {
    assertEquals("models/en-es/model.bin", normalizeZipEntryName("en-es/model.bin", "models"))
    // Already rooted entries are left as-is.
    assertEquals("models/en-es/model.bin", normalizeZipEntryName("models/en-es/model.bin", "models"))
  }

  // --- safeZipEntryTarget: Zip Slip (CWE-22) guard ------------------------

  @Test
  fun `benign entry resolves inside extractTo`() {
    val root = extractTo()
    val target = safeZipEntryTarget(root, "en-es/model.bin", "models")
    assertContained(root, target)
    assertEquals(File(root, "models/en-es/model.bin").canonicalPath, target!!.canonicalPath)
  }

  @Test
  fun `traversal entry without install root is rejected`() {
    val root = extractTo()
    // Would have resolved to <parent>/databases/evil.db before the guard.
    assertNull(safeZipEntryTarget(root, "../../../databases/evil.db", null))
  }

  @Test
  fun `traversal entry escapes even when an install root is present`() {
    val root = extractTo()
    // Re-rooting to "models/" absorbs only ONE `..`, so multiple `..` still escape.
    assertNull(safeZipEntryTarget(root, "../../../../evil.so", "models"))
  }

  @Test
  fun `leading-slash traversal is rejected`() {
    val root = extractTo()
    assertNull(safeZipEntryTarget(root, "/../../evil", null))
  }

  @Test
  fun `blank and dot entries are skipped`() {
    val root = extractTo()
    assertNull(safeZipEntryTarget(root, "", null))
    assertNull(safeZipEntryTarget(root, "./", null))
    assertNull(safeZipEntryTarget(root, "/", null))
  }

  @Test
  fun `a valid entry named exactly like the root is contained`() {
    val root = extractTo()
    assertContained(root, safeZipEntryTarget(root, "models", "models"))
  }
}
