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

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * Bungee's mutable title builder, sent as an Adventure title.
 */
final class BungeeTitle implements net.md_5.bungee.api.Title {

  private Component title = Component.empty();
  private Component subTitle = Component.empty();
  private int fadeIn = 10;
  private int stay = 70;
  private int fadeOut = 20;
  private boolean clear;
  private boolean reset;

  @Override
  public net.md_5.bungee.api.Title title(final BaseComponent text) {
    this.title = Chat.toAdventure(text);
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title title(final BaseComponent... text) {
    this.title = Chat.toAdventure(text);
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title subTitle(final BaseComponent text) {
    this.subTitle = Chat.toAdventure(text);
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title subTitle(final BaseComponent... text) {
    this.subTitle = Chat.toAdventure(text);
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title fadeIn(final int ticks) {
    this.fadeIn = ticks;
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title stay(final int ticks) {
    this.stay = ticks;
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title fadeOut(final int ticks) {
    this.fadeOut = ticks;
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title clear() {
    this.clear = true;
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title reset() {
    this.reset = true;
    return this;
  }

  @Override
  public net.md_5.bungee.api.Title send(final ProxiedPlayer player) {
    if (player instanceof BungeePlayer bungeePlayer) {
      sendTo(bungeePlayer.velocity());
    }
    return this;
  }

  void sendTo(final com.velocitypowered.api.proxy.Player player) {
    if (reset) {
      player.resetTitle();
    }
    if (clear) {
      player.clearTitle();
    }
    player.showTitle(Title.title(title, subTitle, Title.Times.times(ticks(fadeIn), ticks(stay),
        ticks(fadeOut))));
  }

  private static Duration ticks(final int ticks) {
    return Duration.ofMillis(Math.max(0, ticks) * 50L);
  }
}
