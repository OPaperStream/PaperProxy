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
 * Fired after a player joined a party, also for the leader of a new party.
 *
 * @param party the party
 * @param player the player
 */
public record PartyJoinEvent(Party party, Player player) {
}
