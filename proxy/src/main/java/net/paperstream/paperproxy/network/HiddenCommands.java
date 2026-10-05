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

package net.paperstream.paperproxy.network;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.proxy.VelocityServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Removes configured commands from the tab completion list players get. The commands still
 * work, they are only not suggested.
 */
public final class HiddenCommands {

  /** Permission that shows hidden commands anyway. */
  public static final String BYPASS = "paperproxy.tabcomplete.bypass";

  private final VelocityServer server;

  /**
   * Creates the listener.
   *
   * @param server the proxy
   */
  public HiddenCommands(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Strips hidden commands from the command tree.
   *
   * @param event the event
   */
  @Subscribe
  public void onAvailableCommands(final PlayerAvailableCommandsEvent event) {
    final Set<String> hidden = server.getPaperProxyConfig().values().hiddenCommands();
    if (hidden.isEmpty() || event.getPlayer().hasPermission(BYPASS)) {
      return;
    }
    final RootCommandNode<?> root = event.getRootNode();
    final List<String> names = new ArrayList<>();
    for (final CommandNode<?> node : root.getChildren()) {
      if (isHidden(node, hidden)) {
        names.add(node.getName());
      }
    }
    names.forEach(root::removeChildByName);
  }

  static boolean isHidden(final CommandNode<?> node, final Set<String> hidden) {
    String name = node.getName().toLowerCase(Locale.ROOT);
    if (hidden.contains(name)) {
      return true;
    }
    // "plugin:command" forms are hidden together with the plain name.
    final int colon = name.indexOf(':');
    if (colon >= 0) {
      name = name.substring(colon + 1);
      return hidden.contains(name);
    }
    return false;
  }
}
