/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.event;

import com.velocitypowered.api.proxy.Player;
import net.paperstream.paperproxy.api.party.Party;

/**
 * Fired after a player left a party.
 *
 * @param party the party
 * @param player the player
 * @param reason why the player left
 */
public record PartyLeaveEvent(Party party, Player player, Reason reason) {

  /** Why a player left a party. */
  public enum Reason {
    /** Left on their own or through the API. */
    LEFT,
    /** Removed by the leader. */
    KICKED,
    /** Logged out. */
    DISCONNECTED,
    /** The party was disbanded. */
    DISBANDED
  }
}
