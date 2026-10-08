/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PrivateAddressTest {

  private static boolean check(final String ip) throws Exception {
    return InitialLoginSessionHandler.isPrivateAddress(InetAddress.getByName(ip));
  }

  @Test
  void privateAddressesAreNotSentToMojang() throws Exception {
    assertTrue(check("10.0.0.1"));
    assertTrue(check("192.168.1.20"));
    assertTrue(check("172.16.5.5"));
    assertTrue(check("127.0.0.1"));
    assertTrue(check("100.101.102.103"));
    assertTrue(check("fd12:3456::1"));
    assertTrue(check("::1"));
  }

  @Test
  void publicAddressesAreChecked() throws Exception {
    assertFalse(check("1.1.1.1"));
    assertFalse(check("100.128.0.1"));
    assertFalse(check("172.32.0.1"));
    assertFalse(check("2a01:4f8::1"));
  }
}
