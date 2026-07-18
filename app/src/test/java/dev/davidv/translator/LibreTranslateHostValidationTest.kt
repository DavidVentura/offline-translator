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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreTranslateHostValidationTest {
  // Loopback + raw IP literals are what real LibreTranslate clients target.
  @Test
  fun `loopback and ip literal hosts are allowed`() {
    for (host in
      listOf(
        null,
        "",
        "localhost",
        "localhost:5000",
        "127.0.0.1",
        "127.0.0.1:5000",
        "192.168.1.5:5000",
        "10.0.0.2",
        "0.0.0.0:5000",
        "::1",
        "[::1]:5000",
        "[fe80::1]:5000",
      )) {
      assertTrue("expected host '$host' to be allowed", LibreTranslateHttpServer.isAllowedHost(host))
    }
  }

  // DNS-rebinding relies on a registered domain name that re-resolves to a
  // loopback/LAN address; those must be rejected.
  @Test
  fun `registered domain names are rejected`() {
    for (host in
      listOf(
        "evil.com",
        "evil.com:5000",
        "attacker.example.org",
        "translator.attacker.com:5000",
        "myphone.local",
        // rebinding-service style names that embed the target IP as a label
        "app.127.0.0.1.nip.io",
        "127.0.0.1.evil.com",
      )) {
      assertFalse("expected host '$host' to be rejected", LibreTranslateHttpServer.isAllowedHost(host))
    }
  }
}
