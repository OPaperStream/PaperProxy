/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.event;

/**
 * Fired when a backend server goes offline or comes back online.
 *
 * @param server the server name
 * @param online the new state
 */
public record BackendHealthChangeEvent(String server, boolean online) {
}
