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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.party.Parties;

/**
 * /party with invite, accept, leave, kick, disband, list and chat, plus /pc for party chat.
 */
public final class PartyCommand {

  private PartyCommand() {
  }

  /**
   * Registers the commands.
   *
   * @param server the proxy
   */
  public static void register(final VelocityServer server) {
    final Parties parties = server.getParties();
    final SuggestionProvider<CommandSource> players = (ctx, builder) -> {
      final String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
      server.getAllPlayers().stream().map(Player::getUsername)
          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
          .forEach(builder::suggest);
      return builder.buildFuture();
    };
    final LiteralArgumentBuilder<CommandSource> party = BrigadierCommand
        .literalArgumentBuilder("party")
        .requires(source -> source instanceof Player)
        .executes(ctx -> help(ctx))
        .then(BrigadierCommand.literalArgumentBuilder("invite")
            .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                .suggests(players)
                .executes(ctx -> withTarget(server, ctx, target -> {
                  final String key = parties.invite(player(ctx), target);
                  reply(ctx, key, target.getUsername());
                }))))
        .then(BrigadierCommand.literalArgumentBuilder("accept")
            .executes(ctx -> {
              reply(ctx, parties.accept(player(ctx), null), "");
              return Command.SINGLE_SUCCESS;
            })
            .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                .suggests(players)
                .executes(ctx -> withTarget(server, ctx, target ->
                    reply(ctx, parties.accept(player(ctx), target), target.getUsername())))))
        .then(BrigadierCommand.literalArgumentBuilder("leave").executes(ctx -> {
          if (!parties.leave(player(ctx))) {
            reply(ctx, "paperproxy.party.not-in-party", "");
          }
          return Command.SINGLE_SUCCESS;
        }))
        .then(BrigadierCommand.literalArgumentBuilder("kick")
            .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                .suggests(players)
                .executes(ctx -> withTarget(server, ctx, target ->
                    reply(ctx, parties.kick(player(ctx), target), target.getUsername())))))
        .then(BrigadierCommand.literalArgumentBuilder("disband").executes(ctx -> {
          if (!parties.disband(player(ctx))) {
            reply(ctx, "paperproxy.party.not-leader", "");
          }
          return Command.SINGLE_SUCCESS;
        }))
        .then(BrigadierCommand.literalArgumentBuilder("list").executes(ctx -> {
          final Optional<Parties.Party> mine = parties.of(player(ctx));
          if (mine.isEmpty()) {
            reply(ctx, "paperproxy.party.not-in-party", "");
          } else {
            ctx.getSource().sendMessage(Component.translatable("paperproxy.party.list",
                Argument.string("players", String.join(", ", parties.names(mine.get())))));
          }
          return Command.SINGLE_SUCCESS;
        }))
        .then(BrigadierCommand.literalArgumentBuilder("chat")
            .then(BrigadierCommand.requiredArgumentBuilder("message",
                    StringArgumentType.greedyString())
                .executes(ctx -> chat(parties, ctx))));
    registerNode(server, party);
    registerNode(server, BrigadierCommand.literalArgumentBuilder("pc")
        .requires(source -> source instanceof Player)
        .then(BrigadierCommand.requiredArgumentBuilder("message",
                StringArgumentType.greedyString())
            .executes(ctx -> chat(parties, ctx))));
  }

  private static int chat(final Parties parties, final CommandContext<CommandSource> ctx) {
    final Player player = player(ctx);
    final Optional<Parties.Party> mine = parties.of(player);
    if (mine.isEmpty()) {
      reply(ctx, "paperproxy.party.not-in-party", "");
      return 0;
    }
    parties.broadcast(mine.get(), Component.translatable("paperproxy.party.chat",
        Argument.string("player", player.getUsername()),
        Argument.string("message", StringArgumentType.getString(ctx, "message"))));
    return Command.SINGLE_SUCCESS;
  }

  private static int help(final CommandContext<CommandSource> ctx) {
    ctx.getSource().sendMessage(Component.translatable("paperproxy.party.help"));
    return Command.SINGLE_SUCCESS;
  }

  private static Player player(final CommandContext<CommandSource> ctx) {
    return (Player) ctx.getSource();
  }

  private static void reply(final CommandContext<CommandSource> ctx, final String key,
                            final String player) {
    if (!key.isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable(key,
          Argument.string("player", player)));
    }
  }

  private static int withTarget(final VelocityServer server,
                                final CommandContext<CommandSource> ctx,
                                final java.util.function.Consumer<Player> action) {
    final Optional<Player> target = server.getPlayer(StringArgumentType.getString(ctx, "player"));
    if (target.isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.find.not-online",
          Argument.string("player", StringArgumentType.getString(ctx, "player"))));
      return 0;
    }
    action.accept(target.get());
    return Command.SINGLE_SUCCESS;
  }

  private static void registerNode(final VelocityServer server,
                               final LiteralArgumentBuilder<CommandSource> node) {
    final BrigadierCommand command = new BrigadierCommand(node);
    server.getCommandManager().register(server.getCommandManager().metaBuilder(command)
        .plugin(VelocityVirtualPlugin.INSTANCE).build(), command);
  }
}
