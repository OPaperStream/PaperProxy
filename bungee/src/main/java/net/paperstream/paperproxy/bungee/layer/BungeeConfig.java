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

package net.paperstream.paperproxy.bungee.layer;

import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ProxyConfig;
import net.md_5.bungee.api.config.ConfigurationAdapter;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;

/**
 * Bungee's view of the proxy configuration, read live from velocity.toml.
 */
final class BungeeConfig implements ProxyConfig {

  private final BungeeLayer layer;
  private final String uuid = UUID.randomUUID().toString();

  BungeeConfig(final BungeeLayer layer) {
    this.layer = layer;
  }

  private VelocityConfiguration velocity() {
    return layer.velocity().getConfiguration();
  }

  /**
   * Builds the single listener PaperProxy has.
   *
   * @param layer the layer
   * @return the listener
   */
  static ListenerInfo listener(final BungeeLayer layer) {
    final VelocityConfiguration config = layer.velocity().getConfiguration();
    final Map<String, String> forcedHosts = new LinkedHashMap<>();
    config.getForcedHosts().forEach((host, servers) -> {
      if (!servers.isEmpty()) {
        forcedHosts.put(host, servers.getFirst());
      }
    });
    return new ListenerInfo(config.getBind(), Chat.toLegacy(config.getMotd()),
        config.getShowMaxPlayers(), 60, List.copyOf(config.getAttemptConnectionOrder()), false,
        forcedHosts, "GLOBAL_PING", false, false, config.getQueryPort(), config.isQueryEnabled(),
        config.isProxyProtocol());
  }

  @Override
  public int getTimeout() {
    return velocity().getReadTimeout();
  }

  @Override
  public String getUuid() {
    return uuid;
  }

  @Override
  public Collection<ListenerInfo> getListeners() {
    return List.of(layer.listenerInfo());
  }

  @Override
  @Deprecated
  public Map<String, ServerInfo> getServers() {
    return layer.serverMap();
  }

  @Override
  public boolean isOnlineMode() {
    return velocity().isOnlineMode();
  }

  @Override
  public boolean isLogCommands() {
    return velocity().isLogCommandExecutions();
  }

  @Override
  public int getRemotePingCache() {
    return -1;
  }

  @Override
  public int getPlayerLimit() {
    return -1;
  }

  @Override
  public Collection<String> getDisabledCommands() {
    return List.of();
  }

  @Override
  public int getServerConnectTimeout() {
    return velocity().getConnectTimeout();
  }

  @Override
  public int getRemotePingTimeout() {
    return velocity().getConnectTimeout();
  }

  @Override
  @Deprecated
  public int getThrottle() {
    return velocity().getLoginRatelimit();
  }

  @Override
  @Deprecated
  public boolean isIpForward() {
    return velocity().getPlayerInfoForwardingMode() != PlayerInfoForwarding.NONE;
  }

  @Override
  @Deprecated
  public String getFavicon() {
    final Favicon favicon = getFaviconObject();
    return favicon == null ? null : favicon.getEncoded();
  }

  @Override
  public Favicon getFaviconObject() {
    return velocity().getFavicon().map(f -> Favicon.create(f.getBase64Url())).orElse(null);
  }

  /**
   * Answers {@code ProxyServer#getConfigurationAdapter()} from the same data.
   */
  static final class Adapter implements ConfigurationAdapter {

    private final BungeeLayer layer;

    Adapter(final BungeeLayer layer) {
      this.layer = layer;
    }

    @Override
    public void load() {
    }

    @Override
    public int getInt(final String path, final int def) {
      return def;
    }

    @Override
    public String getString(final String path, final String def) {
      return def;
    }

    @Override
    public boolean getBoolean(final String path, final boolean def) {
      return def;
    }

    @Override
    public Collection<?> getList(final String path, final Collection<?> def) {
      return def;
    }

    @Override
    public Map<String, ServerInfo> getServers() {
      return layer.serverMap();
    }

    @Override
    public Collection<ListenerInfo> getListeners() {
      return List.of(layer.listenerInfo());
    }

    @Override
    public Collection<String> getGroups(final String player) {
      return List.of();
    }

    @Override
    public Collection<String> getPermissions(final String group) {
      return List.of();
    }
  }
}
