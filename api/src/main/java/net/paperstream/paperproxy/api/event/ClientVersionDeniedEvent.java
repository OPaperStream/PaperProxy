/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.event;

import com.velocitypowered.api.proxy.Player;

/**
 * Fired when a player is refused a server because its client version is not allowed there.
 *
 * @param player the player
 * @param server the server name
 * @param allowed the allowed versions as configured
 */
public record ClientVersionDeniedEvent(Player player, String server, String allowed) {
}
