/*
 * Copyright (C) 2026 PaperProxy Contributors
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

package net.paperstream.paperproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

class PaperProxyApiTest {

  private final PaperProxyApi api = new PaperProxyApi(null);

  @Test
  void buildsMessages() {
    final TranslatableComponent message = (TranslatableComponent) api.getMessage(
        "plugin.welcome", "player", "Alice");
    assertEquals("plugin.welcome", message.key());
    assertEquals(1, message.arguments().size());
  }

  @Test
  void rejectsBrokenPlaceholders() {
    assertThrows(IllegalArgumentException.class, () -> api.getMessage("k", "player"));
    final NullPointerException value = assertThrows(NullPointerException.class,
        () -> api.getMessage("k", "player", null));
    assertTrue(value.getMessage().contains("player"));
    assertThrows(NullPointerException.class, () -> api.getMessage("k", null, "x"));
    assertThrows(NullPointerException.class, () -> api.getMessage(null));
  }

  @Test
  void shutdownWaitsForAsyncTasks() {
    final AtomicBoolean done = new AtomicBoolean();
    api.async(() -> {
      try {
        Thread.sleep(300);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      done.set(true);
    });
    api.shutdown(5);
    assertTrue(done.get());
  }
}
