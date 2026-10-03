/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api.event;

/**
 * Fired after a plugin was loaded or unloaded with {@code /paperproxy plugin}. Plugins that
 * depend on another plugin can use it to reconnect to the new instance.
 *
 * @param plugin the plugin id or name
 * @param loaded true after loading, false after unloading
 */
public record PluginRuntimeChangeEvent(String plugin, boolean loaded) {
}
