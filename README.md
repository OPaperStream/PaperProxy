<p align="center">
  <img src="assets/logo.png" alt="PaperProxy" width="600">
</p>

> **NOT AN OFFICIAL PAPER PROJECT.** PaperProxy is not affiliated with or endorsed by
> PaperMC. It is an independent fork of [Velocity](https://github.com/PaperMC/Velocity).

PaperProxy is a Minecraft proxy that runs **Velocity plugins** and **BungeeCord/Waterfall
plugins** side by side on one proxy, and fixes the things that annoy server owners about
Velocity and Waterfall today.

## Status

**Early development.** There is no release yet and nothing here is ready for production.

Done so far:

- PaperProxy branding with a start banner that always shows the "not official" notice
- `messages.yml`: every proxy message editable, MiniMessage and `&` codes, `{placeholders}`,
  optional per-client-language files, automatic merge of new keys after updates
- `/paperproxy` (alias `/pp`) with `info` and `reload`
- Own bStats page

Everything else listed below is planned.

## Planned features

- **Velocity and BungeeCord plugins together.** Bungee plugins load through a built-in
  compatibility layer based on the original BungeeCord API.
- **Forwarding per server.** Mix `modern`, `paperguard` and `legacy` backends in one network.
- **PaperGuard.** Replay-proof, signed player forwarding for servers that cannot use modern
  forwarding (including 1.8), with a backend bridge plugin. Fail-closed by design.
- **messages.yml.** Every message the proxy shows is editable in one file
  (MiniMessage and `&` color codes).
- **Plugin reload, load and unload** without restarting, with leak detection and a clear
  warning that it is experimental.
- **Fully async.** Per-player event queues, Virtual Threads for blocking plugin code and a
  watchdog that names slow plugins.
- **ViaVersion integration.** Installer, version rules per server and a warning when Via runs
  on both proxy and backend.
- **Health checks, maintenance mode** and the commands Velocity is missing
  (`/alert`, `/find`, `/send`, `/ip`).
- **Update notifications** and an optional auto-updater (off by default, signed releases).
- **A small API** that feels like Velocity and Bungee, so there is nothing new to learn.

## Building

Requires Java 25 (same as current Velocity).

```bash
./gradlew build
```

The proxy JAR ends up in `proxy/build/libs/paperproxy-<version>.jar`.

## Metrics

PaperProxy reports anonymous usage statistics to
[bStats](https://bstats.org/plugin/server-implementation/PaperProxy/34471).
They can be disabled in `plugins/bStats/config.txt`.

## Community

Questions and feedback: [Discord](https://dc.gg/paperstream)

## Credits and license

PaperProxy is based on [Velocity](https://github.com/PaperMC/Velocity) by the Velocity
contributors and PaperMC. "Paper" and "Velocity" are names of PaperMC projects; PaperProxy
is not one of them.

Licensed under the [GNU General Public License v3.0](LICENSE), like Velocity.
