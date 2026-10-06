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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.api.event.PartyFollowEvent;
import net.paperstream.paperproxy.api.event.PartyJoinEvent;
import net.paperstream.paperproxy.api.event.PartyLeaveEvent;
import net.paperstream.paperproxy.api.party.PartyManager;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Parties: a leader and members who follow the leader from server to server.
 */
public final class Parties implements PartyManager {

  /** One party. The first member is the leader. */
  public final class Party implements net.paperstream.paperproxy.api.party.Party {
    private final UUID id = UUID.randomUUID();
    private final List<UUID> members = new CopyOnWriteArrayList<>();
    private volatile boolean active = true;

    Party(final UUID leader) {
      members.add(leader);
    }

    @Override
    public UUID getId() {
      return id;
    }

    /**
     * Returns the leader's UUID.
     *
     * @return the leader
     */
    public UUID leader() {
      return members.get(0);
    }

    /**
     * Returns all member UUIDs, leader first.
     *
     * @return the members
     */
    public List<UUID> members() {
      return List.copyOf(members);
    }

    @Override
    public Player getLeader() {
      return server.getPlayer(leader()).orElseThrow();
    }

    @Override
    public List<Player> getMembers() {
      final List<Player> out = new ArrayList<>();
      for (final UUID uuid : members) {
        server.getPlayer(uuid).ifPresent(out::add);
      }
      return List.copyOf(out);
    }

    @Override
    public boolean isMember(final Player player) {
      return members.contains(player.getUniqueId());
    }

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public void sendMessage(final Component message) {
      getMembers().forEach(p -> p.sendMessage(message));
    }

    Parties outer() {
      return Parties.this;
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

  @Override
  public Optional<net.paperstream.paperproxy.api.party.Party> getParty(final Player player) {
    return Optional.ofNullable(byPlayer.get(player.getUniqueId()));
  }

  @Override
  public Collection<net.paperstream.paperproxy.api.party.Party> getParties() {
    return List.copyOf(new java.util.LinkedHashSet<>(byPlayer.values()));
  }

  @Override
  public synchronized Party create(final Player leader) {
    if (byPlayer.containsKey(leader.getUniqueId())) {
      throw new IllegalStateException(leader.getUsername() + " is already in a party");
    }
    final Party party = new Party(leader.getUniqueId());
    byPlayer.put(leader.getUniqueId(), party);
    server.getEventManager().fireAndForget(new PartyJoinEvent(party, leader));
    return party;
  }

  @Override
  public synchronized boolean addMember(final net.paperstream.paperproxy.api.party.Party party,
                                        final Player player) {
    final Party own = own(party);
    if (!own.active || byPlayer.containsKey(player.getUniqueId())
        || own.members.size() >= server.getPaperProxyConfig().values().party().maxSize()) {
      return false;
    }
    own.members.add(player.getUniqueId());
    byPlayer.put(player.getUniqueId(), own);
    invites.remove(player.getUniqueId());
    own.sendMessage(Component.translatable("paperproxy.party.joined",
        Argument.string("player", player.getUsername())));
    server.getEventManager().fireAndForget(new PartyJoinEvent(own, player));
    // Bring the new member to the leader.
    server.getPlayer(own.leader()).flatMap(Player::getCurrentServer)
        .ifPresent(target -> follow(own, player, target.getServer()));
    return true;
  }

  @Override
  public boolean removeMember(final Player player) {
    return remove(player, PartyLeaveEvent.Reason.LEFT);
  }

  @Override
  public synchronized boolean setLeader(final net.paperstream.paperproxy.api.party.Party party,
                                        final Player player) {
    final Party own = own(party);
    if (!own.members.remove(player.getUniqueId())) {
      return false;
    }
    own.members.add(0, player.getUniqueId());
    return true;
  }

  @Override
  public synchronized void disband(final net.paperstream.paperproxy.api.party.Party party) {
    final Party own = own(party);
    if (!own.active) {
      return;
    }
    own.sendMessage(Component.translatable("paperproxy.party.disbanded"));
    final List<Player> members = own.getMembers();
    own.members.forEach(byPlayer::remove);
    own.members.clear();
    own.active = false;
    for (final Player member : members) {
      server.getEventManager().fireAndForget(new PartyLeaveEvent(own, member,
          PartyLeaveEvent.Reason.DISBANDED));
    }
  }

  private Party own(final net.paperstream.paperproxy.api.party.Party party) {
    if (!(party instanceof Party own) || own.outer() != this) {
      throw new IllegalArgumentException("Not a PaperProxy party");
    }
    return own;
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
      party = create(from);
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
   * @return a message key describing the result, empty on success
   */
  public synchronized String accept(final Player player, final @Nullable Player leader) {
    if (byPlayer.containsKey(player.getUniqueId())) {
      return "paperproxy.party.already-in-party";
    }
    final Map<UUID, Invite> mine = invites.getOrDefault(player.getUniqueId(), Map.of());
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
    if (invite == null || invite.expires() < System.currentTimeMillis()
        || !invite.party().active) {
      return "paperproxy.party.no-invite";
    }
    return addMember(invite.party(), player) ? "" : "paperproxy.party.full";
  }

  /**
   * Leaves the party.
   *
   * @param player the player
   * @return true if the player was in a party
   */
  public boolean leave(final Player player) {
    return remove(player, PartyLeaveEvent.Reason.LEFT);
  }

  private synchronized boolean remove(final Player player, final PartyLeaveEvent.Reason reason) {
    final Party party = byPlayer.remove(player.getUniqueId());
    if (party == null) {
      return false;
    }
    party.members.remove(player.getUniqueId());
    server.getEventManager().fireAndForget(new PartyLeaveEvent(party, player, reason));
    if (party.members.size() <= 1) {
      disband(party);
    } else {
      party.sendMessage(Component.translatable("paperproxy.party.left",
          Argument.string("player", player.getUsername())));
    }
    return true;
  }

  /**
   * Removes a member, only the leader may do this.
   *
   * @param leader the leader
   * @param target the member
   * @return a message key describing the result, empty on success
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
    remove(target, PartyLeaveEvent.Reason.KICKED);
    return "";
  }

  /**
   * Disbands the party, only the leader may do this.
   *
   * @param leader the leader
   * @return false if the player leads no party
   */
  public synchronized boolean disbandAsLeader(final Player leader) {
    final Party party = byPlayer.get(leader.getUniqueId());
    if (party == null || !party.leader().equals(leader.getUniqueId())) {
      return false;
    }
    disband(party);
    return true;
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
    for (final Player member : party.getMembers()) {
      if (!member.getUniqueId().equals(party.leader())) {
        follow(party, member, event.getServer());
      }
    }
  }

  private void follow(final Party party, final Player member, final RegisteredServer target) {
    final boolean there = member.getCurrentServer()
        .map(current -> current.getServer().equals(target)).orElse(false);
    if (there) {
      return;
    }
    server.getEventManager().fire(new PartyFollowEvent(party, member, target))
        .thenAccept(event -> {
          if (!event.isCancelled()) {
            member.sendMessage(Component.translatable("paperproxy.party.following",
                Argument.string("server", target.getServerInfo().getName())));
            member.createConnectionRequest(target).fireAndForget();
          }
        });
  }

  /**
   * Members who log out leave their party.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    invites.remove(event.getPlayer().getUniqueId());
    remove(event.getPlayer(), PartyLeaveEvent.Reason.DISCONNECTED);
  }

  /**
   * Returns the online members' names.
   *
   * @param party the party
   * @return the names, leader first
   */
  public List<String> names(final Party party) {
    return party.getMembers().stream().map(Player::getUsername).toList();
  }
}
