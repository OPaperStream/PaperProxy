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
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.translation.GlobalTranslator;
import net.paperstream.paperproxy.sync.NetworkSync;

/**
 * The BungeeCord commands Velocity is missing: {@code /alert}, {@code /find} and {@code /ip},
 * plus {@code /queue} and {@code /hub}.
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
      final String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
      server.getAllPlayers().stream().map(Player::getUsername)
          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
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
              final Component alert = Component.translatable("paperproxy.alert.format",
                  Argument.component("message", text));
              server.sendMessage(alert);
              // Rendered here, because other proxies may have different messages.yml texts.
              server.getNetworkSync().broadcast(GlobalTranslator.render(alert, Locale.US));
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

    registerQueueAndHub(server);
  }

  private static void registerQueueAndHub(final VelocityServer server) {
    registerCommand(server, new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("queue")
        .requires(source -> source instanceof Player)
        .executes(ctx -> {
          final Player player = (Player) ctx.getSource();
          final Optional<Object[]> position = server.getServerQueue()
              .position(player.getUniqueId());
          if (position.isEmpty()) {
            player.sendMessage(Component.translatable("paperproxy.queue.not-queued"));
            return 0;
          }
          player.sendMessage(Component.translatable("paperproxy.queue.status",
              Argument.string("server", String.valueOf(position.get()[0])),
              Argument.string("position", String.valueOf(position.get()[1])),
              Argument.string("total", String.valueOf(position.get()[2]))));
          return Command.SINGLE_SUCCESS;
        })
        .then(BrigadierCommand.literalArgumentBuilder("leave").executes(ctx -> {
          final Player player = (Player) ctx.getSource();
          final String left = server.getServerQueue().leave(player.getUniqueId());
          player.sendMessage(left == null
              ? Component.translatable("paperproxy.queue.not-queued")
              : Component.translatable("paperproxy.queue.left", Argument.string("server", left)));
          return Command.SINGLE_SUCCESS;
        }))));

    final List<String> aliases = server.getPaperProxyConfig().values().hubAliases();
    if (aliases.isEmpty()) {
      return;
    }
    final BrigadierCommand hub = new BrigadierCommand(BrigadierCommand
        .literalArgumentBuilder(aliases.get(0))
        .requires(source -> source instanceof Player
            && !server.getPaperProxyConfig().values().hubTarget().isEmpty())
        .executes(ctx -> hub(server, (Player) ctx.getSource())));
    server.getCommandManager().register(server.getCommandManager().metaBuilder(hub)
        .aliases(aliases.subList(1, aliases.size()).toArray(String[]::new))
        .plugin(VelocityVirtualPlugin.INSTANCE).build(), hub);
  }

  private static int hub(final VelocityServer server, final Player player) {
    final String target = server.getPaperProxyConfig().values().hubTarget();
    final String current = player.getCurrentServer()
        .map(c -> c.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("");
    final boolean inGroup = server.getServerGroups().members(target).contains(current);
    if (current.equals(target) || inGroup) {
      player.sendMessage(Component.translatable("paperproxy.hub.already"));
      return 0;
    }
    final Optional<RegisteredServer> best = server.getServerGroups().best(player, target, null);
    if (best.isPresent()) {
      player.createConnectionRequest(best.get()).fireAndForget();
      return Command.SINGLE_SUCCESS;
    }
    final Optional<RegisteredServer> single = server.getServer(target);
    if (single.isPresent() && server.getPaperProxyConfig().values().queue().enabled()
        && server.getNetworkRules().hardProblem(player, single.get()) == null) {
      server.getServerQueue().enqueue(player, single.get());
      return Command.SINGLE_SUCCESS;
    }
    player.sendMessage(Component.translatable("paperproxy.hub.no-server"));
    return 0;
  }

  private static int find(final VelocityServer server, final CommandContext<CommandSource> ctx) {
    final String name = StringArgumentType.getString(ctx, "player");
    final Optional<Player> player = server.getPlayer(name);
    if (player.isEmpty()) {
      final Optional<NetworkSync.Location> remote = server.getNetworkSync().find(name);
      if (remote.isPresent()) {
        ctx.getSource().sendMessage(Component.translatable("paperproxy.find.result-network",
            Argument.string("player", remote.get().name()),
            Argument.string("server", remote.get().server().isEmpty() ? "-"
                : remote.get().server()),
            Argument.string("proxy", remote.get().proxy())));
        return Command.SINGLE_SUCCESS;
      }
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
