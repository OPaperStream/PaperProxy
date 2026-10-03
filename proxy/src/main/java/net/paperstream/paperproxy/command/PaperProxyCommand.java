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

package net.paperstream.paperproxy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.util.ProxyVersion;
import com.velocitypowered.proxy.VelocityServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.PaperProxyBranding;

/**
 * Implements {@code /paperproxy} (alias {@code /pp}).
 */
public final class PaperProxyCommand {

  private static final TextColor BRAND_COLOR = TextColor.color(0x3ad9cd);

  private PaperProxyCommand() {
    throw new AssertionError();
  }

  /**
   * Creates the command.
   *
   * @param server the proxy
   * @return the command
   */
  public static BrigadierCommand create(final VelocityServer server) {
    return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("paperproxy")
        // Everyone may see the version and the notice unless explicitly denied.
        .requires(source -> source.getPermissionValue("paperproxy.command.info") != Tristate.FALSE)
        .executes(ctx -> info(server, ctx))
        .then(BrigadierCommand.literalArgumentBuilder("info")
            .executes(ctx -> info(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("reload")
            .requires(source -> source.getPermissionValue("paperproxy.command.reload")
                == Tristate.TRUE)
            .executes(ctx -> reload(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("maintenance")
            .requires(source -> source.getPermissionValue("paperproxy.command.maintenance")
                == Tristate.TRUE)
            .executes(ctx -> maintenanceStatus(server, ctx))
            .then(BrigadierCommand.literalArgumentBuilder("on")
                .executes(ctx -> maintenance(server, ctx, null, true))
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                    .suggests(PaperProxyCommand.serverSuggestions(server))
                    .executes(ctx -> maintenance(server, ctx,
                        StringArgumentType.getString(ctx, "server"), true))))
            .then(BrigadierCommand.literalArgumentBuilder("off")
                .executes(ctx -> maintenance(server, ctx, null, false))
                .then(BrigadierCommand.requiredArgumentBuilder("server", StringArgumentType.word())
                    .suggests(PaperProxyCommand.serverSuggestions(server))
                    .executes(ctx -> maintenance(server, ctx,
                        StringArgumentType.getString(ctx, "server"), false)))))
        .then(PluginCommand.create(server))
        .then(BrigadierCommand.literalArgumentBuilder("servers")
            .requires(source -> source.getPermissionValue("paperproxy.command.servers")
                == Tristate.TRUE)
            .executes(ctx -> servers(server, ctx)))
        .then(BrigadierCommand.literalArgumentBuilder("paperguard")
            // Keys are secrets: console only, never shown in game.
            .requires(source -> source instanceof ConsoleCommandSource)
            .then(BrigadierCommand.literalArgumentBuilder("key")
                .then(BrigadierCommand.requiredArgumentBuilder("server",
                        StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                      server.getAllServers().forEach(s ->
                          builder.suggest(s.getServerInfo().getName()));
                      return builder.buildFuture();
                    })
                    .executes(ctx -> paperGuardKey(server, ctx))))
            .then(BrigadierCommand.literalArgumentBuilder("rotate")
                .then(BrigadierCommand.literalArgumentBuilder("confirm")
                    .executes(ctx -> paperGuardRotate(server, ctx)))
                .executes(ctx -> {
                  ctx.getSource().sendMessage(Component.translatable(
                      "paperproxy.paperguard.rotate-confirm"));
                  return Command.SINGLE_SUCCESS;
                })))
        .build());
  }

  private static int info(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final CommandSource source = ctx.getSource();
    final ProxyVersion version = server.getVersion();

    source.sendMessage(Component.text()
        .content(version.getName() + " ")
        .decoration(TextDecoration.BOLD, true)
        .color(BRAND_COLOR)
        .append(Component.text(version.getVersion()).decoration(TextDecoration.BOLD, false))
        .hoverEvent(Component.translatable("velocity.command.version-offer-copy-version"))
        .clickEvent(ClickEvent.copyToClipboard(version.getName() + " " + version.getVersion()))
        .build());
    source.sendMessage(Component.translatable("paperproxy.command.disclaimer"));
    source.sendMessage(Component.translatable("paperproxy.command.based-on",
        Argument.string("velocity_version", PaperProxyBranding.velocityVersion())));
    source.sendMessage(Component.text()
        .append(link("GitHub", PaperProxyBranding.REPOSITORY_URL))
        .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
        .append(link("Discord", PaperProxyBranding.DISCORD_URL))
        .build());
    return Command.SINGLE_SUCCESS;
  }

  static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSource> serverSuggestions(
      final VelocityServer server) {
    return (ctx, builder) -> {
      server.getAllServers().forEach(s -> builder.suggest(s.getServerInfo().getName()));
      return builder.buildFuture();
    };
  }

  private static int maintenanceStatus(final VelocityServer server,
                                       final CommandContext<CommandSource> ctx) {
    final var values = server.getPaperProxyConfig().values();
    ctx.getSource().sendMessage(Component.translatable("paperproxy.maintenance.status",
        Argument.string("state", values.maintenance() ? "on" : "off"),
        Argument.string("servers", values.maintenanceServers().isEmpty() ? "-"
            : String.join(", ", values.maintenanceServers()))));
    return Command.SINGLE_SUCCESS;
  }

  private static int maintenance(final VelocityServer server,
                                 final CommandContext<CommandSource> ctx,
                                 final String target, final boolean enabled) {
    if (target != null && server.getServer(target).isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("velocity.command.server-does-not-exist",
          Component.text(target)));
      return 0;
    }
    try {
      server.getPaperProxyConfig().setMaintenance(target, enabled);
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to save paperproxy.toml: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    server.getPingCache().clear();
    ctx.getSource().sendMessage(Component.translatable(enabled
            ? "paperproxy.maintenance.enabled" : "paperproxy.maintenance.disabled",
        Argument.string("target", target == null ? "network" : target)));
    return Command.SINGLE_SUCCESS;
  }

  private static int servers(final VelocityServer server,
                             final CommandContext<CommandSource> ctx) {
    final var values = server.getPaperProxyConfig().values();
    for (final var registered : server.getAllServers()) {
      final String name = registered.getServerInfo().getName();
      final String lower = name.toLowerCase(java.util.Locale.ROOT);
      final String state = values.maintenanceServers().contains(lower) ? "maintenance"
          : server.getHealthChecker().status(name).name().toLowerCase(java.util.Locale.ROOT);
      ctx.getSource().sendMessage(Component.translatable("paperproxy.servers.line",
          Argument.string("server", name),
          Argument.string("state", state),
          Argument.string("forwarding", server.getForwarding().modeFor(name).name()
              .toLowerCase(java.util.Locale.ROOT)),
          Argument.string("players", String.valueOf(registered.getPlayersConnected().size()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int paperGuardKey(final VelocityServer server,
                                   final CommandContext<CommandSource> ctx) {
    final String name = StringArgumentType.getString(ctx, "server");
    if (server.getServer(name).isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("velocity.command.server-does-not-exist",
          Component.text(name)));
      return 0;
    }
    try {
      server.getForwarding().loadSecret();
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to load paperguard.secret: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.paperguard.key",
        Argument.string("server", name),
        Argument.string("key", server.getForwarding().serverKey(name))));
    return Command.SINGLE_SUCCESS;
  }

  private static int paperGuardRotate(final VelocityServer server,
                                      final CommandContext<CommandSource> ctx) {
    try {
      server.getForwarding().rotateSecret();
    } catch (final java.io.IOException e) {
      ctx.getSource().sendMessage(Component.text("Unable to write paperguard.secret: "
          + e.getMessage(), NamedTextColor.RED));
      return 0;
    }
    ctx.getSource().sendMessage(Component.translatable("paperproxy.paperguard.rotated"));
    return Command.SINGLE_SUCCESS;
  }

  private static int reload(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    server.getPaperProxyConfig().load();
    final boolean success = server.getMessages().load();
    ctx.getSource().sendMessage(Component.translatable(success
        ? "paperproxy.command.reload-success" : "paperproxy.command.reload-failure"));
    return success ? Command.SINGLE_SUCCESS : 0;
  }

  private static Component link(final String label, final String url) {
    return Component.text()
        .content(label)
        .color(NamedTextColor.GREEN)
        .decoration(TextDecoration.UNDERLINED, true)
        .clickEvent(ClickEvent.openUrl(url))
        .build();
  }
}
