/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.party;

import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * A party: a leader and members who follow the leader from server to server.
 */
public interface Party {

  /**
   * Returns the id of this party. It stays the same for the life of the party.
   *
   * @return the id
   */
  UUID getId();

  /**
   * Returns the leader.
   *
   * @return the leader
   */
  Player getLeader();

  /**
   * Returns all members, the leader first.
   *
   * @return the members
   */
  List<Player> getMembers();

  /**
   * Tells whether a player is in this party.
   *
   * @param player the player
   * @return true if the player is a member or the leader
   */
  boolean isMember(Player player);

  /**
   * Tells whether the party still exists.
   *
   * @return false after it was disbanded
   */
  boolean isActive();

  /**
   * Sends a message to every member.
   *
   * @param message the message
   */
  void sendMessage(Component message);
}
