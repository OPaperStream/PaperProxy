/*
 * Copyright (C) 2020-2023 Velocity Contributors
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

package com.velocitypowered.proxy.command.builtin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;

/**
 * Implements the Velocity default {@code /send} command.
 */
public class SendCommand {
  private final VelocityServer server;
  private static final String SERVER_ARG = "server";
  private static final String PLAYER_ARG = "player";

  public SendCommand(VelocityServer server) {
    this.server = server;
  }

  /**
   * Registers this command.
   */
  public void register() {
    final LiteralArgumentBuilder<CommandSource> rootNode = BrigadierCommand
        .literalArgumentBuilder("send")
        .requires(source ->
            source.getPermissionValue("velocity.command.send") == Tristate.TRUE)
        .executes(this::usage);
    final RequiredArgumentBuilder<CommandSource, String> playerNode = BrigadierCommand
        .requiredArgumentBuilder(PLAYER_ARG, StringArgumentType.word())
        .suggests((context, builder) -> {
          final String argument = context.getArguments().containsKey(PLAYER_ARG)
              ? context.getArgument(PLAYER_ARG, String.class)
              : "";
          for (final Player player : server.getAllPlayers()) {
            final String playerName = player.getUsername();
            if (playerName.regionMatches(true, 0, argument, 0, argument.length())) {
              builder.suggest(playerName);
            }
          }
          if ("all".regionMatches(true, 0, argument, 0, argument.length())) {
            builder.suggest("all");
          }
          if ("current".regionMatches(true, 0, argument, 0, argument.length())
              && context.getSource() instanceof Player) {
            builder.suggest("current");
          }
          return builder.buildFuture();
        })
        .executes(this::usage);
    final ArgumentCommandNode<CommandSource, String> serverNode = BrigadierCommand
        .requiredArgumentBuilder(SERVER_ARG, StringArgumentType.word())
        .suggests((context, builder) -> {
          final String argument = context.getArguments().containsKey(SERVER_ARG)
              ? context.getArgument(SERVER_ARG, String.class)
              : "";
          for (final RegisteredServer server : server.getAllServers()) {
            final String serverName = server.getServerInfo().getName();
            if (serverName.regionMatches(true, 0, argument, 0, argument.length())) {
              builder.suggest(server.getServerInfo().getName());
            }
          }
          for (final String group : this.server.getPaperProxyConfig().values().groups().keySet()) {
            if (group.regionMatches(true, 0, argument, 0, argument.length())) {
              builder.suggest(group);
            }
          }
          return builder.buildFuture();
        })
        .executes(this::send)
        .build();
    playerNode.then(serverNode);
    // PaperProxy: /send server <from> <to> moves everyone on one server.
    rootNode.then(BrigadierCommand.literalArgumentBuilder("server")
        .then(BrigadierCommand.requiredArgumentBuilder("from", StringArgumentType.word())
            .suggests(serverNode.getCustomSuggestions())
            .then(BrigadierCommand.requiredArgumentBuilder(SERVER_ARG, StringArgumentType.word())
                .suggests(serverNode.getCustomSuggestions())
                .executes(ctx -> send(ctx, "server:"
                    + ctx.getArgument("from", String.class))))));
    rootNode.then(playerNode.build());
    final BrigadierCommand command = new BrigadierCommand(rootNode);
    server.getCommandManager().register(
        server.getCommandManager().metaBuilder(command)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        command
    );
  }

  private int usage(final CommandContext<CommandSource> context) {
    context.getSource().sendMessage(
        Component.translatable("velocity.command.send-usage", NamedTextColor.YELLOW)
    );
    return Command.SINGLE_SUCCESS;
  }

  private int send(final CommandContext<CommandSource> context) {
    return send(context, context.getArgument(PLAYER_ARG, String.class));
  }

  private int send(final CommandContext<CommandSource> context, final String player) {
    final String serverName = context.getArgument(SERVER_ARG, String.class);

    // PaperProxy: the target may also be a server group, then every player goes to the
    // emptiest usable member.
    final boolean group = server.getPaperProxyConfig().values().groups()
        .containsKey(serverName.toLowerCase(Locale.ROOT));
    final Optional<RegisteredServer> maybeServer = server.getServer(serverName);

    if (maybeServer.isEmpty() && !group) {
      context.getSource().sendMessage(
          CommandMessages.SERVER_DOES_NOT_EXIST.arguments(Argument.string("server", serverName))
      );
      return 0;
    }

    final Collection<Player> targets;
    if (Objects.equals(player, "all")) {
      targets = server.getAllPlayers();
    } else if (Objects.equals(player, "current")) {
      if (!(context.getSource() instanceof Player source)) {
        context.getSource().sendMessage(CommandMessages.PLAYERS_ONLY);
        return 0;
      }
      final Optional<ServerConnection> connectedServer = source.getCurrentServer();
      if (connectedServer.isEmpty()) {
        return 0;
      }
      targets = connectedServer.get().getServer().getPlayersConnected();
    } else if (player.regionMatches(true, 0, "server:", 0, 7)) {
      final Optional<RegisteredServer> from = server.getServer(player.substring(7));
      if (from.isEmpty()) {
        context.getSource().sendMessage(CommandMessages.SERVER_DOES_NOT_EXIST
            .arguments(Argument.string("server", player.substring(7))));
        return 0;
      }
      targets = from.get().getPlayersConnected();
    } else {
      final Optional<Player> maybePlayer = server.getPlayer(player);
      if (maybePlayer.isEmpty()) {
        context.getSource().sendMessage(
            CommandMessages.PLAYER_NOT_FOUND.arguments(Argument.string("player", player))
        );
        return 0;
      }
      targets = List.of(maybePlayer.get());
    }

    for (final Player p : List.copyOf(targets)) {
      final Optional<RegisteredServer> target = group
          ? server.getServerGroups().best(p, serverName,
              p.getCurrentServer().map(ServerConnection::getServer).orElse(null))
          : maybeServer;
      target.ifPresent(t -> p.createConnectionRequest(t).fireAndForget());
    }
    return Command.SINGLE_SUCCESS;
  }
}
