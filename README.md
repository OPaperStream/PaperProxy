<p align="center">
  <img src="assets/logo.png" alt="PaperProxy" width="600">
</p>

> **NOT AN OFFICIAL PAPER PROJECT.** PaperProxy is not affiliated with or endorsed by
> PaperMC. It is an independent fork of [Velocity](https://github.com/PaperMC/Velocity).

PaperProxy is a Minecraft proxy that runs **Velocity plugins** and **BungeeCord/Waterfall
plugins** side by side on one proxy, and fixes the things that annoy server owners about
Velocity and Waterfall today.

**Status: early development, no release yet.** Everything below is implemented and tested on
a test network, but not yet in production use. Expect rough edges.

## Features

### BungeeCord plugins on Velocity

Drop BungeeCord plugins into `plugins/` next to your Velocity plugins. They run on the original
BungeeCord API, implemented on top of Velocity:

- Plugins load through BungeeCord's own plugin manager (dependencies, `libraries`, lifecycle)
- Players, servers, commands with tab completion, permissions (LuckPerms works), titles,
  action bars, tab list header, cookies, server links, scheduler, YAML/JSON config
- Login, ping, connect, kick, chat, command, plugin message and settings events, including
  async intents
- `getServers().put(...)` registers servers like on BungeeCord
- The layer runs in its own class loader and only starts when a BungeeCord plugin is present;
  Velocity plugins never see BungeeCord classes

Not supported: plugins that use BungeeCord internals (raw packets, `net.md_5.bungee.protocol`,
Netty pipeline), proxy side scoreboards and dialogs. Such calls log a clear message.

### Forwarding per server and PaperGuard

Each backend can use its own forwarding mode in `paperproxy.toml`:

```toml
[forwarding.servers]
lobby = "modern"
old-pvp = "paperguard"
```

**PaperGuard** is signed, replay-proof forwarding for servers that cannot use modern forwarding.
Every login is signed with HMAC-SHA256 over all player data, the target server name, a timestamp
and a one-time nonce, using a key derived per server. The backend plugin **PaperProxy-Bridge**
(Paper 1.12.2+, Folia) verifies it and **rejects everyone when it is not configured** (fail
closed). Replayed, expired, modified or foreign logins are rejected; this is covered by attack
tests in the build.

Setup: set the server to `paperguard`, run `paperproxy paperguard key <server>` in the proxy
console, put server name and key into `plugins/PaperProxy-Bridge/config.yml` on the backend and
set `settings.bungeecord: true` in its `spigot.yml`.

### Everything else

- **messages.yml**: every proxy message editable, MiniMessage and `&` codes, `{placeholders}`,
  optional per-client-language files; new keys are merged in after updates, your texts and own
  sections are kept
- **motd.yml**: rotating or random MOTDs, hover lines, text instead of the player count
- **Plugin reload/load/unload** for Velocity and BungeeCord plugins, with confirmation, block
  list and leak detection
- **Watchdog** that names plugins blocking events
- **ViaVersion**: `paperproxy via install|update` from Modrinth, allowed client versions per
  server, warning when Via runs on proxy and backend
- **Health checks**: offline servers are skipped and refused with a message, staff is notified
- **Maintenance** for the whole network or single servers, with whitelist
- **Ping cache** against server list floods
- `/alert`, `/find`, `/ip`
- **Update notifications** from GitHub releases and an optional, signature-verified
  auto-updater (off by default)
- **API** for plugins (MIT licensed): `PaperProxy.get()`

## Commands

| Command | Permission |
|---|---|
| `/paperproxy` (`/pp`) | everyone, unless `paperproxy.command.info` is denied |
| `/pp reload` | `paperproxy.command.reload` |
| `/pp servers` | `paperproxy.command.servers` |
| `/pp maintenance [on\|off] [server]` | `paperproxy.command.maintenance` |
| `/pp plugin list\|reload\|unload\|load` | `paperproxy.command.plugin` |
| `/pp via install\|update` | `paperproxy.command.via` |
| `/pp update` | `paperproxy.update` |
| `pp paperguard key\|rotate` | console only |
| `/alert`, `/find`, `/ip` | `paperproxy.command.alert`, `.find`, `.ip` |

Other permissions: `paperproxy.maintenance.bypass`, `paperproxy.notify.health`,
`paperproxy.update` (update notice on join).

## API

```java
PaperProxy pp = PaperProxy.get();
pp.getClientVersion(player);                  // "1.21.4"
pp.getMessage("myplugin.welcome", "player", name);
pp.isOnline("survival");
pp.async(() -> database.save(player));
```

Events: `BackendHealthChangeEvent`, `ClientVersionDeniedEvent`, `PluginRuntimeChangeEvent`.
Everything else is the normal Velocity API.

## Building

Requires Java 25 (same as current Velocity).

```bash
./gradlew build
```

- Proxy: `proxy/build/libs/paperproxy-<version>.jar`
- Backend plugin: `bridge/build/libs/PaperProxy-Bridge-<version>.jar`

## Metrics

PaperProxy reports anonymous usage statistics to
[bStats](https://bstats.org/plugin/server-implementation/PaperProxy/34471).
They can be disabled in `plugins/bStats/config.txt`.

## Community

Questions and feedback: [Discord](https://dc.gg/paperstream)

## Credits and license

PaperProxy is based on [Velocity](https://github.com/PaperMC/Velocity) by the Velocity
contributors and PaperMC. "Paper" and "Velocity" are names of PaperMC projects; PaperProxy
is not one of them. The BungeeCord compatibility layer uses the BungeeCord API by md_5
(BSD license).

PaperProxy is licensed under the [GNU General Public License v3.0](LICENSE), like Velocity.
The API (`api/`) is MIT licensed.
