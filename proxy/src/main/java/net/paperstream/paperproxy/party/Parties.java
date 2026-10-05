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

package net.paperstream.paperproxy.party;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Parties: a leader and members who follow the leader from server to server.
 */
public final class Parties {

  /** One party. The first member is the leader. */
  public static final class Party {
    private final List<UUID> members = new CopyOnWriteArrayList<>();

    Party(final UUID leader) {
      members.add(leader);
    }

    /**
     * Returns the leader.
     *
     * @return the leader's UUID
     */
    public UUID leader() {
      return members.get(0);
    }

    /**
     * Returns all members, leader first.
     *
     * @return the members
     */
    public List<UUID> members() {
      return List.copyOf(members);
    }
  }

  private record Invite(Party party, long expires) {
  }

  private static final long INVITE_MILLIS = TimeUnit.SECONDS.toMillis(60);

  private final VelocityServer server;
  private final Map<UUID, Party> byPlayer = new ConcurrentHashMap<>();
  private final Map<UUID, Map<UUID, Invite>> invites = new ConcurrentHashMap<>();

  /**
   * Creates the party manager.
   *
   * @param server the proxy
   */
  public Parties(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Returns the party of a player.
   *
   * @param player the player
   * @return the party, empty if none
   */
  public Optional<Party> of(final Player player) {
    return Optional.ofNullable(byPlayer.get(player.getUniqueId()));
  }

  /**
   * Invites a player, creating a party for the inviter if needed.
   *
   * @param from the inviter
   * @param to the invited player
   * @return a message key describing the result
   */
  public synchronized String invite(final Player from, final Player to) {
    if (from.equals(to)) {
      return "paperproxy.party.invite-self";
    }
    Party party = byPlayer.get(from.getUniqueId());
    if (party != null && !party.leader().equals(from.getUniqueId())) {
      return "paperproxy.party.not-leader";
    }
    if (byPlayer.containsKey(to.getUniqueId())) {
      return "paperproxy.party.already-in-party";
    }
    final int max = server.getPaperProxyConfig().values().party().maxSize();
    if (party != null && party.members.size() >= max) {
      return "paperproxy.party.full";
    }
    if (party == null) {
      party = new Party(from.getUniqueId());
      byPlayer.put(from.getUniqueId(), party);
    }
    invites.computeIfAbsent(to.getUniqueId(), k -> new ConcurrentHashMap<>())
        .put(from.getUniqueId(), new Invite(party, System.currentTimeMillis() + INVITE_MILLIS));
    to.sendMessage(Component.translatable("paperproxy.party.invited",
        Argument.string("player", from.getUsername())));
    return "paperproxy.party.invite-sent";
  }

  /**
   * Accepts an invite.
   *
   * @param player the invited player
   * @param leader the inviter, or null for the newest invite
   * @return a message key describing the result
   */
  public synchronized String accept(final Player player, final @Nullable Player leader) {
    if (byPlayer.containsKey(player.getUniqueId())) {
      return "paperproxy.party.already-in-party";
    }
    final Map<UUID, Invite> mine = invites.getOrDefault(player.getUniqueId(), Map.of());
    final long now = System.currentTimeMillis();
    Invite invite = null;
    if (leader != null) {
      invite = mine.get(leader.getUniqueId());
    } else {
      for (final Invite candidate : mine.values()) {
        if (invite == null || candidate.expires() > invite.expires()) {
          invite = candidate;
        }
      }
    }
    if (invite == null || invite.expires() < now
        || byPlayer.get(invite.party().leader()) != invite.party()) {
      return "paperproxy.party.no-invite";
    }
    final int max = server.getPaperProxyConfig().values().party().maxSize();
    if (invite.party().members.size() >= max) {
      return "paperproxy.party.full";
    }
    invites.remove(player.getUniqueId());
    invite.party().members.add(player.getUniqueId());
    byPlayer.put(player.getUniqueId(), invite.party());
    broadcast(invite.party(), Component.translatable("paperproxy.party.joined",
        Argument.string("player", player.getUsername())));
    // Bring the new member to the leader.
    server.getPlayer(invite.party().leader()).flatMap(Player::getCurrentServer)
        .ifPresent(target -> send(player, target.getServer()));
    return "";
  }

  /**
   * Leaves the party. A leaving leader hands the party to the next member.
   *
   * @param player the player
   * @return true if the player was in a party
   */
  public synchronized boolean leave(final Player player) {
    final Party party = byPlayer.remove(player.getUniqueId());
    if (party == null) {
      return false;
    }
    party.members.remove(player.getUniqueId());
    if (party.members.size() <= 1) {
      party.members.forEach(byPlayer::remove);
      broadcast(party, Component.translatable("paperproxy.party.disbanded"));
      party.members.clear();
    } else {
      broadcast(party, Component.translatable("paperproxy.party.left",
          Argument.string("player", player.getUsername())));
    }
    return true;
  }

  /**
   * Removes a member, only the leader may do this.
   *
   * @param leader the leader
   * @param target the member
   * @return a message key describing the result
   */
  public synchronized String kick(final Player leader, final Player target) {
    final Party party = byPlayer.get(leader.getUniqueId());
    if (party == null || !party.leader().equals(leader.getUniqueId())) {
      return "paperproxy.party.not-leader";
    }
    if (byPlayer.get(target.getUniqueId()) != party || leader.equals(target)) {
      return "paperproxy.party.not-member";
    }
    target.sendMessage(Component.translatable("paperproxy.party.kicked"));
    leave(target);
    return "";
  }

  /**
   * Disbands the party, only the leader may do this.
   *
   * @param leader the leader
   * @return false if the player leads no party
   */
  public synchronized boolean disband(final Player leader) {
    final Party party = byPlayer.get(leader.getUniqueId());
    if (party == null || !party.leader().equals(leader.getUniqueId())) {
      return false;
    }
    broadcast(party, Component.translatable("paperproxy.party.disbanded"));
    party.members.forEach(byPlayer::remove);
    party.members.clear();
    return true;
  }

  /**
   * Sends a message to every online member.
   *
   * @param party the party
   * @param message the message
   */
  public void broadcast(final Party party, final Component message) {
    for (final UUID id : party.members()) {
      server.getPlayer(id).ifPresent(p -> p.sendMessage(message));
    }
  }

  /**
   * Members follow their leader to every server.
   *
   * @param event the event
   */
  @Subscribe
  public void onServerConnected(final ServerConnectedEvent event) {
    final Party party = byPlayer.get(event.getPlayer().getUniqueId());
    if (party == null || !party.leader().equals(event.getPlayer().getUniqueId())
        || !server.getPaperProxyConfig().values().party().follow()) {
      return;
    }
    final RegisteredServer target = event.getServer();
    for (final UUID id : party.members()) {
      if (!id.equals(party.leader())) {
        server.getPlayer(id).ifPresent(member -> send(member, target));
      }
    }
  }

  private void send(final Player member, final RegisteredServer target) {
    final boolean there = member.getCurrentServer()
        .map(current -> current.getServer().equals(target)).orElse(false);
    if (!there) {
      member.sendMessage(Component.translatable("paperproxy.party.following",
          Argument.string("server", target.getServerInfo().getName())));
      member.createConnectionRequest(target).fireAndForget();
    }
  }

  /**
   * Members who log out leave their party.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    invites.remove(event.getPlayer().getUniqueId());
    leave(event.getPlayer());
  }

  /**
   * Returns the online members' names.
   *
   * @param party the party
   * @return the names, leader first
   */
  public Set<String> names(final Party party) {
    final Set<String> out = new java.util.LinkedHashSet<>();
    for (final UUID id : party.members()) {
      server.getPlayer(id).ifPresent(p -> out.add(p.getUsername()));
    }
    return out;
  }
}
