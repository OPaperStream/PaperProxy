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

import com.velocitypowered.api.command.CommandSource;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.event.PermissionCheckEvent;

/**
 * Shared {@link CommandSender} logic. Permissions come from Velocity (so LuckPerms and friends
 * work), plus anything set through the Bungee API, and Bungee permission plugins get their say
 * through {@link PermissionCheckEvent} like on BungeeCord.
 */
abstract class AbstractSender implements CommandSender {

  protected final BungeeLayer layer;
  private final Set<String> groups = ConcurrentHashMap.newKeySet();
  private final Set<String> permissions = ConcurrentHashMap.newKeySet();

  AbstractSender(final BungeeLayer layer) {
    this.layer = layer;
  }

  /**
   * Returns the Velocity side of this sender.
   *
   * @return the source
   */
  abstract CommandSource source();

  @Override
  public void sendMessage(final String message) {
    source().sendMessage(Chat.fromLegacy(message));
  }

  @Override
  public void sendMessage(final BaseComponent... message) {
    source().sendMessage(Chat.toAdventure(message));
  }

  @Override
  public void sendMessage(final BaseComponent message) {
    source().sendMessage(Chat.toAdventure(message));
  }

  @Override
  public void sendMessages(final String... messages) {
    for (final String message : messages) {
      sendMessage(message);
    }
  }

  @Override
  public Collection<String> getGroups() {
    return Collections.unmodifiableSet(groups);
  }

  @Override
  public void addGroups(final String... groups) {
    for (final String group : groups) {
      this.groups.add(group.toLowerCase(Locale.ROOT));
    }
  }

  @Override
  public void removeGroups(final String... groups) {
    for (final String group : groups) {
      this.groups.remove(group.toLowerCase(Locale.ROOT));
    }
  }

  @Override
  public boolean hasPermission(final String permission) {
    final String lower = permission.toLowerCase(Locale.ROOT);
    final boolean base = source().hasPermission(permission) || permissions.contains(lower);
    return layer.pluginManager().callEvent(new PermissionCheckEvent(this, permission, base))
        .hasPermission();
  }

  @Override
  public void setPermission(final String permission, final boolean value) {
    if (value) {
      permissions.add(permission.toLowerCase(Locale.ROOT));
    } else {
      permissions.remove(permission.toLowerCase(Locale.ROOT));
    }
  }

  @Override
  public Collection<String> getPermissions() {
    return Collections.unmodifiableSet(permissions);
  }
}
