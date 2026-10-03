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

package net.paperstream.paperproxy.bungee.layer;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mirrors the commands Bungee plugins register into Velocity's command system, so they show up
 * in the client's command list and tab completion. Execution goes through Bungee's own
 * dispatcher, which keeps permission messages and argument handling exactly as on BungeeCord.
 */
final class CommandBridge {

  private static final Logger logger = LogManager.getLogger(CommandBridge.class);

  private final BungeeLayer layer;
  /** Alias -> the Bungee command currently registered for it. */
  private final Map<String, Command> registered = new HashMap<>();

  CommandBridge(final BungeeLayer layer) {
    this.layer = layer;
  }

  /**
   * Registers new Bungee commands with Velocity and removes vanished ones. Cheap when nothing
   * changed, so it can run periodically.
   */
  synchronized void sync() {
    final Map<String, Command> current = new HashMap<>();
    for (final Map.Entry<String, Command> entry : layer.pluginManager().getCommands()) {
      current.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
    }
    final CommandManager manager = layer.velocity().getCommandManager();

    for (final String alias : List.copyOf(registered.keySet())) {
      if (current.get(alias) != registered.get(alias)) {
        manager.unregister(alias);
        registered.remove(alias);
      }
    }
    for (final Map.Entry<String, Command> entry : current.entrySet()) {
      final String alias = entry.getKey();
      if (registered.containsKey(alias)) {
        continue;
      }
      if (manager.hasCommand(alias)) {
        logger.warn("BungeeCord command /{} is shadowed by a Velocity command with the same name",
            alias);
        registered.put(alias, entry.getValue());
        continue;
      }
      final CommandMeta meta = manager.metaBuilder(alias).plugin(layer).build();
      manager.register(meta, new Bridged(alias, entry.getValue()));
      registered.put(alias, entry.getValue());
    }
  }

  /**
   * Removes every mirrored command.
   */
  synchronized void clear() {
    final CommandManager manager = layer.velocity().getCommandManager();
    for (final String alias : registered.keySet()) {
      manager.unregister(alias);
    }
    registered.clear();
  }

  private final class Bridged implements RawCommand {

    private final String alias;
    private final Command command;

    Bridged(final String alias, final Command command) {
      this.alias = alias;
      this.command = command;
    }

    @Override
    public void execute(final Invocation invocation) {
      final CommandSender sender = layer.sender(invocation.source());
      final String arguments = invocation.arguments();
      layer.pluginManager().dispatchCommand(sender,
          arguments.isEmpty() ? alias : alias + " " + arguments);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(final Invocation invocation) {
      final CommandSender sender = layer.sender(invocation.source());
      final List<String> results = new ArrayList<>();
      layer.pluginManager().dispatchCommand(sender, alias + " " + invocation.arguments(),
          results);
      return CompletableFuture.completedFuture(results);
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
      return command.hasPermission(layer.sender(invocation.source()));
    }
  }
}
