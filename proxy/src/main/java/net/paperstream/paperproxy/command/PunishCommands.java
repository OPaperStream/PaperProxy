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
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.punish.Punishments;
import net.paperstream.paperproxy.punish.Punishments.Entry;
import net.paperstream.paperproxy.punish.Punishments.Type;

/**
 * /ban, /unban, /mute, /unmute, /kick and /banlist. Only registered when punish.enabled is on,
 * so backend and plugin commands of the same name keep working otherwise.
 */
public final class PunishCommands {

  /** Players with this permission cannot be banned, muted or kicked. */
  public static final String EXEMPT = "paperproxy.punish.exempt";

  private PunishCommands() {
  }

  /**
   * Registers the commands.
   *
   * @param server the proxy
   */
  public static void register(final VelocityServer server) {
    final SuggestionProvider<CommandSource> players = (ctx, builder) -> {
      final String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
      server.getAllPlayers().stream().map(Player::getUsername)
          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
          .forEach(builder::suggest);
      return builder.buildFuture();
    };
    punish(server, "ban", Type.BAN, players);
    punish(server, "mute", Type.MUTE, players);
    pardon(server, "unban", Type.BAN);
    pardon(server, "unmute", Type.MUTE);

    registerNode(server, BrigadierCommand.literalArgumentBuilder("kick")
        .requires(source -> has(source, "paperproxy.command.kick"))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
            .suggests(players)
            .executes(ctx -> kick(server, ctx, ""))
            .then(BrigadierCommand.requiredArgumentBuilder("reason",
                    StringArgumentType.greedyString())
                .executes(ctx -> kick(server, ctx, StringArgumentType.getString(ctx,
                    "reason"))))));

    registerNode(server, BrigadierCommand.literalArgumentBuilder("banlist")
        .requires(source -> has(source, "paperproxy.command.ban"))
        .executes(ctx -> {
          final List<Entry> bans = server.getPunishments().list(Type.BAN);
          ctx.getSource().sendMessage(Component.translatable("paperproxy.punish.list",
              Argument.string("count", String.valueOf(bans.size()))));
          for (final Entry entry : bans) {
            ctx.getSource().sendMessage(Component.translatable("paperproxy.punish.list-line",
                Argument.string("player", entry.name()),
                Argument.string("until", entry.until() == 0 ? "-"
                    : Punishments.date(entry.until())),
                Argument.string("reason", entry.reason().isEmpty() ? "-" : entry.reason())));
          }
          return Command.SINGLE_SUCCESS;
        }));
  }

  private static void punish(final VelocityServer server, final String name, final Type type,
                             final SuggestionProvider<CommandSource> players) {
    registerNode(server, BrigadierCommand.literalArgumentBuilder(name)
        .requires(source -> has(source, "paperproxy.command." + name))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
            .suggests(players)
            .executes(ctx -> punish(server, ctx, type, ""))
            .then(BrigadierCommand.requiredArgumentBuilder("duration and reason",
                    StringArgumentType.greedyString())
                .executes(ctx -> punish(server, ctx, type,
                    StringArgumentType.getString(ctx, "duration and reason"))))));
  }

  private static int punish(final VelocityServer server, final CommandContext<CommandSource> ctx,
                            final Type type, final String rest) {
    final String target = StringArgumentType.getString(ctx, "player");
    final Optional<Player> online = server.getPlayer(target);
    if (online.isPresent() && online.get().hasPermission(EXEMPT)) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.punish.exempt",
          Argument.string("player", online.get().getUsername())));
      return 0;
    }
    final String[] parts = rest.isBlank() ? new String[0] : rest.trim().split(" ", 2);
    Duration duration = null;
    String reason = rest.trim();
    if (parts.length > 0) {
      duration = Punishments.parseDuration(parts[0]);
      if (duration != null) {
        reason = parts.length > 1 ? parts[1].trim() : "";
      }
    }
    final long now = System.currentTimeMillis();
    final String by = ctx.getSource() instanceof Player p ? p.getUsername() : "Console";
    final Entry entry = new Entry(type,
        online.map(Player::getUniqueId).map(UUID::toString).orElse(null),
        online.map(Player::getUsername).orElse(target).toLowerCase(Locale.ROOT), reason, by, now,
        duration == null ? 0 : now + duration.toMillis());
    server.getPunishments().add(entry);
    ctx.getSource().sendMessage(Component.translatable(type == Type.BAN
            ? "paperproxy.punish.banned" : "paperproxy.punish.muted-done",
        Argument.string("player", entry.name()),
        Argument.string("until", duration == null ? "permanent"
            : Punishments.date(entry.until()))));
    return Command.SINGLE_SUCCESS;
  }

  private static void pardon(final VelocityServer server, final String name, final Type type) {
    registerNode(server, BrigadierCommand.literalArgumentBuilder(name)
        .requires(source -> has(source, "paperproxy.command." + (type == Type.BAN ? "ban"
            : "mute")))
        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
            .suggests((ctx, builder) -> {
              final String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
              server.getPunishments().list(type).stream().map(Entry::name)
                  .filter(n -> n.startsWith(prefix)).forEach(builder::suggest);
              return builder.buildFuture();
            })
            .executes(ctx -> {
              final String player = StringArgumentType.getString(ctx, "player");
              final boolean removed = server.getPunishments().remove(type, player);
              ctx.getSource().sendMessage(Component.translatable(removed
                      ? "paperproxy.punish.pardoned" : "paperproxy.punish.not-punished",
                  Argument.string("player", player)));
              return removed ? Command.SINGLE_SUCCESS : 0;
            })));
  }

  private static int kick(final VelocityServer server, final CommandContext<CommandSource> ctx,
                          final String reason) {
    final Optional<Player> player = server.getPlayer(StringArgumentType.getString(ctx, "player"));
    if (player.isEmpty()) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.find.not-online",
          Argument.string("player", StringArgumentType.getString(ctx, "player"))));
      return 0;
    }
    if (player.get().hasPermission(EXEMPT)) {
      ctx.getSource().sendMessage(Component.translatable("paperproxy.punish.exempt",
          Argument.string("player", player.get().getUsername())));
      return 0;
    }
    player.get().disconnect(Component.translatable("paperproxy.punish.kick-screen",
        Argument.string("reason", reason.isEmpty() ? "-" : reason)));
    ctx.getSource().sendMessage(Component.translatable("paperproxy.punish.kicked",
        Argument.string("player", player.get().getUsername())));
    return Command.SINGLE_SUCCESS;
  }

  private static boolean has(final CommandSource source, final String permission) {
    return source.getPermissionValue(permission) == Tristate.TRUE;
  }

  private static void registerNode(final VelocityServer server,
                               final com.mojang.brigadier.builder.LiteralArgumentBuilder<
                                   CommandSource> node) {
    final BrigadierCommand command = new BrigadierCommand(node);
    server.getCommandManager().register(server.getCommandManager().metaBuilder(command)
        .plugin(VelocityVirtualPlugin.INSTANCE).build(), command);
  }
}
