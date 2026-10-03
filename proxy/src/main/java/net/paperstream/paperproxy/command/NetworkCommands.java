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
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.translation.Argument;

/**
 * The BungeeCord commands Velocity is missing: {@code /alert}, {@code /find} and {@code /ip}.
 */
public final class NetworkCommands {

  private NetworkCommands() {
    throw new AssertionError();
  }

  /**
   * Registers the commands.
   *
   * @param server the proxy
   */
  public static void register(final VelocityServer server) {
    final SuggestionProvider<CommandSource> players = (ctx, builder) -> {
      final String prefix = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
      server.getAllPlayers().stream().map(Player::getUsername)
          .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
          .forEach(builder::suggest);
      return builder.buildFuture();
    };

    registerCommand(server, new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("alert")
        .requires(source -> has(source, "paperproxy.command.alert"))
        .then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
            .executes(ctx -> {
              // Staff may use MiniMessage and & colors in alerts.
              final Component text = MiniMessage.miniMessage().deserialize(
                  net.paperstream.paperproxy.messages.MessageFormatter.legacyToMiniMessage(
                      StringArgumentType.getString(ctx, "message")));
              server.sendMessage(Component.translatable("paperproxy.alert.format",
                  Argument.component("message", text)));
              return Command.SINGLE_SUCCESS;
            }))));

    registerCommand(server, new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("find")
        .requires(source -> has(source, "paperproxy.command.find"))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
            .suggests(players)
            .executes(ctx -> find(server, ctx)))));

    registerCommand(server, new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("ip")
        .requires(source -> has(source, "paperproxy.command.ip"))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
            .suggests(players)
            .executes(ctx -> {
              final Optional<Player> player = server.getPlayer(
                  StringArgumentType.getString(ctx, "player"));
              if (player.isEmpty()) {
                return notOnline(ctx);
              }
              ctx.getSource().sendMessage(Component.translatable("paperproxy.ip.result",
                  Argument.string("player", player.get().getUsername()),
                  Argument.string("ip", player.get().getRemoteAddress().getAddress()
                      .getHostAddress())));
              return Command.SINGLE_SUCCESS;
            }))));
  }

  private static int find(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final Optional<Player> player = server.getPlayer(StringArgumentType.getString(ctx, "player"));
    if (player.isEmpty()) {
      return notOnline(ctx);
    }
    final String where = player.get().getCurrentServer()
        .map(connection -> connection.getServerInfo().getName()).orElse("-");
    ctx.getSource().sendMessage(Component.translatable("paperproxy.find.result",
        Argument.string("player", player.get().getUsername()),
        Argument.string("server", where)));
    return Command.SINGLE_SUCCESS;
  }

  private static int notOnline(final CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.translatable("paperproxy.find.not-online",
        Argument.string("player", StringArgumentType.getString(ctx, "player"))));
    return 0;
  }

  private static boolean has(final CommandSource source, final String permission) {
    return source.getPermissionValue(permission) == Tristate.TRUE;
  }

  private static void registerCommand(final VelocityServer server,
                                      final BrigadierCommand command) {
    server.getCommandManager().register(server.getCommandManager().metaBuilder(command)
        .plugin(VelocityVirtualPlugin.INSTANCE).build(), command);
  }
}
