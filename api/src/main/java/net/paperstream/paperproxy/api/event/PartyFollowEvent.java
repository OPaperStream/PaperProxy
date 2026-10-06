/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.event;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.paperstream.paperproxy.api.party.Party;

/**
 * Fired before a member follows the leader to another server. Cancel it to keep the member
 * where they are, for example during a minigame round.
 */
public final class PartyFollowEvent {

  private final Party party;
  private final Player member;
  private final RegisteredServer target;
  private boolean cancelled;

  /**
   * Creates the event.
   *
   * @param party the party
   * @param member the member who would follow
   * @param target the leader's new server
   */
  public PartyFollowEvent(final Party party, final Player member, final RegisteredServer target) {
    this.party = party;
    this.member = member;
    this.target = target;
  }

  /**
   * Returns the party.
   *
   * @return the party
   */
  public Party getParty() {
    return party;
  }

  /**
   * Returns the member who would follow.
   *
   * @return the member
   */
  public Player getMember() {
    return member;
  }

  /**
   * Returns the leader's new server.
   *
   * @return the server
   */
  public RegisteredServer getTarget() {
    return target;
  }

  /**
   * Tells whether the member stays.
   *
   * @return true if cancelled
   */
  public boolean isCancelled() {
    return cancelled;
  }

  /**
   * Keeps the member where they are.
   *
   * @param cancelled true to cancel
   */
  public void setCancelled(final boolean cancelled) {
    this.cancelled = cancelled;
  }
}
