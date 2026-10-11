[![Latest release](https://img.shields.io/github/v/release/DayleSacoto/plasmo-voice?label=Forge%201.7.10)](https://github.com/DayleSacoto/plasmo-voice/releases)
[![Plasmo Voice Discord](http://img.shields.io/discord/833693644501286993?label=Plasmo%20Voice&style=flat&logo=discord)](https://discord.gg/uueEqzwCJJ)
[![GTNH Discord](https://img.shields.io/badge/Discord-GT%20New%20Horizons-5865F2?logo=discord)](https://discord.gg/gtnh)

<div align="center">
  <img src="https://imgur.com/3ccgCRz.png" alt="Plasmo Voice Logo">

  <br>

  <b>Plasmo Voice 2.1.17 backport for Minecraft 1.7.10 / Forge</b>

  <br><br>

  <a href="https://github.com/DayleSacoto/plasmo-voice/releases">Downloads</a>
  <span> • </span>
  <a href="https://github.com/plasmoapp/plasmo-voice">Upstream</a>
  <span> • </span>
  <a href="https://plasmovoice.com">Documentation</a>
  <span> • </span>
  <a href="https://discord.gg/gtnh">GTNH Discord</a>
</div>

---

# Plasmo Voice for Forge 1.7.10

Unofficial manual backport of **Plasmo Voice 2.1.17** for **Minecraft 1.7.10 / Forge**.

Primarily developed and tested for **GT New Horizons** with **lwjgl3ify / LWJGL3 / OpenAL Soft**.

> [!WARNING]
> This is a community-maintained backport and is **not an official Plasmo Voice 1.7.10 release**.
>
> New releases should be tested carefully. Please report reproducible issues with logs when possible.

## Compatibility

**Minecraft 1.7.10** · **Forge 10.13.4.1614** · **GT New Horizons** · **lwjgl3ify / LWJGL3**

The same standalone JAR is installed on both the client and server.

<p align="center">
  <img src="https://i.imgur.com/wCbe0al.png" width="300" alt="Forge 1.7.10 compatibility">
</p>

Players without the mod can still join if vanilla clients are allowed, but they cannot use voice chat.

## Features

**Voice**
- Proximity voice chat
- Push-to-Talk and Voice Activation
- Adjustable voice distance
- Per-player and per-source volume
- Client and server-side mute
- Singleplayer and Open to LAN

**Audio**
- Opus codec
- RNNoise noise suppression
- OpenAL 3D positional audio
- Stereo capture
- Sound Occlusion
- Directional Sources
- HRTF
- Java Sound microphone fallback

**Interface**
- Devices, Volume, Activation, Overlay, Advanced and Hotkeys
- Player voice icons and distance visualization
- Modern GTNH 64x64 skins and second head layers

Press **`V`** to open voice settings.

## Installation

Download the latest release:

**[Forge 1.7.10 Releases](https://github.com/DayleSacoto/plasmo-voice/releases)**

Place the same JAR in the `mods/` directory on both the client and Forge server.

```text
plasmovoice-forge-1.7.10-X.Y.Z.jar
```

A release also has a `-dev` JAR (deobfuscated, for a mod development environment) and a `-sources` JAR. They are for mod developers and are not installed.

## Versioning

Plasmo Voice Legacy Forge uses its own independent release versioning: `0.4.0`, `0.4.1`, `0.5.0`, ... `1.0.0`.

The original manual backport was based on Plasmo Voice 2.1.17. The two version lines are unrelated: Legacy Forge `0.4.0` is **not** upstream Plasmo Voice `0.4.0`, and the voice protocol stays the one of Plasmo Voice 2.1.17.

- **PATCH** - bug fixes that do not intentionally change behavior
- **MINOR** - new backwards-compatible Legacy Forge functionality
- **MAJOR** - significant compatibility changes, once the project reaches a stable 1.x

Releases are tagged `legacy-X.Y.Z` (`legacy-0.4.0`, `legacy-0.4.1`, ...). The in-game update notification reads only these tags.

Legacy Forge is an independent maintenance branch and does not follow upstream releases: a newer upstream Plasmo Voice version is not an update for this mod. Upstream fixes are reviewed by hand and, where they apply, ported into a regular Legacy Forge release.

Releases before `0.4.0` were published as `2.1.17-forge-1.7.10` and its `r1`, `r2` and `r3` revisions. They remain available as historical releases; that numbering is no longer used. Their update notification does not see `legacy-` releases, so updating from them to `0.4.0` is a one-time manual step.

## Server commands

```text
/vlist
/vrc
/vmute
/vunmute
/vmutelist
/vreload
```

`plasmovoice:` aliases are also supported.

## Debugging

For detailed diagnostics:

```properties
debug.enabled=true
```

Useful when reporting microphone, audio, UDP, reconnect or playback issues.

## Backport scope

The goal is to preserve the **core Plasmo Voice 2.1.17 experience** on Forge 1.7.10.

Some modern systems are intentionally outside the current scope, including full addon API parity, modern proxy/platform integrations, PlaceholderAPI, vanish integration and About/Addons functionality.

The backport also includes several GTNH / legacy-specific compatibility changes.

## Community

**GT New Horizons**  
https://discord.gg/gtnh

**Plasmo Voice**  
https://discord.gg/uueEqzwCJJ

## Upstream

Based on **Plasmo Voice 2.1.17** by the Plasmo Voice developers.

- [GitHub](https://github.com/plasmoapp/plasmo-voice)
- [Website](https://plasmovoice.com)
- [Modrinth](https://modrinth.com/mod/plasmo-voice)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/plasmo-voice)

> [!NOTE]
> This Forge 1.7.10 branch is an unofficial community backport and is not maintained by the upstream Plasmo Voice developers.
