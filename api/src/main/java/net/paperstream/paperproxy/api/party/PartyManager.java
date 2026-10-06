/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.party;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.Optional;

/**
 * Creates, finds and changes parties. Get it with {@code PaperProxy.get().getParties()}.
 *
 * <pre>{@code
 * PartyManager parties = PaperProxy.get().getParties();
 * parties.getParty(player).ifPresent(party -> {
 *   for (Player member : party.getMembers()) { ... }
 * });
 * }</pre>
 */
public interface PartyManager {

  /**
   * Returns the party of a player.
   *
   * @param player the player
   * @return the party, empty if the player is in none
   */
  Optional<Party> getParty(Player player);

  /**
   * Returns all parties.
   *
   * @return the parties
   */
  Collection<Party> getParties();

  /**
   * Creates a party with one member.
   *
   * @param leader the leader
   * @return the new party
   * @throws IllegalStateException if the player is already in a party
   */
  Party create(Player leader);

  /**
   * Adds a player to a party without an invite.
   *
   * @param party the party
   * @param player the player
   * @return false if the player is already in a party, the party is full or disbanded
   */
  boolean addMember(Party party, Player player);

  /**
   * Removes a player from their party. A leaving leader hands the party to the next member,
   * a party with one member left is disbanded.
   *
   * @param player the player
   * @return false if the player is in no party
   */
  boolean removeMember(Player player);

  /**
   * Makes another member the leader.
   *
   * @param party the party
   * @param player the new leader, must be a member
   * @return false if the player is not a member
   */
  boolean setLeader(Party party, Player player);

  /**
   * Disbands a party.
   *
   * @param party the party
   */
  void disband(Party party);
}
