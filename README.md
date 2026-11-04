[![Release](https://shieldcn.dev/github/release/DayleSacoto/plasmo-voice.svg?split=true&variant=secondary&size=xs&label=Release&labelColor=232925&labelTextColor=FFFFFF&color=2F70AD&valueColor=29243B&logo=ri:LuTag&logoColor=FFFFFF&iconSize=14&radius=5)](https://github.com/DayleSacoto/plasmo-voice/releases)
[![GTNH](https://shieldcn.dev/badge/GTNH-Compatible-2F70AD.svg?&variant=secondary&size=xs&labelColor=232925&labelTextColor=FFFFFF&valueColor=FFFFFF&logo=ri:LuCircleCheck&logoColor=0FBF3E&iconSize=14&radius=5)](https://www.gtnewhorizons.com/)

<div align="center">
  <img src="https://raw.githubusercontent.com/DayleSacoto/plasmo-voice/assets/branding/banner.png" alt="Plasmo Voice Logo">
  <div>
    <a href="https://www.curseforge.com/minecraft/mc-mods/plasmo-voice-legacy-forge">CurseForge</a>
    <span> | </span>
    <a href="https://github.com/DayleSacoto/plasmo-voice">GitHub</a>
    <span> | </span>
    <a href="https://plasmovoice.com">Documentation</a>
  </div>
</div>

## Bringing players together through voice in the worlds of legacy Minecraft.
Lately, I've been spending a lot of time playing [GregTech New Horizons (GTNH)](https://www.gtnewhorizons.com/). While Discord works fine, I wanted something more natural - a way to talk to friends directly in Minecraft without relying on external voice chat.<br>
I started looking for voice chat mods for Minecraft 1.7.10, but apart from [Gliby's Voice Chat](https://www.curseforge.com/minecraft/mc-mods/glibys-voice-chat), I couldn't find much. That's when I discovered modern alternatives, particularly Plasmo Voice. After exploring its source code and license, I realized that backporting it was entirely possible. And that's how Plasmo Voice Legacy Forge was born.<br>

And here we are!

The project is still young and actively developed. There's plenty of work ahead, from fixing bugs and adapting features to gradually becoming independent of the modern codebase. The goal remains simple - to bring modern voice chat to legacy Minecraft.<br>
Players need the client-side mod to use voice chat. Those without it can still join and play normally, but they won't be able to use voice chat.

## Features

**Plasmo Voice Legacy Forge** brings the core features of the [original Plasmo Voice](https://www.curseforge.com/minecraft/mc-mods/plasmo-voice) to Minecraft 1.7.10, preserving its familiar interface, high-quality audio, and customization options. At the same time, the project is gradually evolving into an independent version with improvements and adaptations for legacy Minecraft, especially GregTech New Horizons (GTNH).

### Voice Chat
- **Proximity Voice Chat** - Communicate with nearby players based on their distance and position in the world.
- **Push-to-Talk & Voice Activation** - Choose between pressing a key to speak or automatic microphone activation.
- **Individual Volume Control** - Adjust the volume of each player independently.
- **Adjustable Proximity Distance** - Customize how far away other players can hear your voice.

### Audio & Immersion
- **3D Positional Audio** - Spatial audio powered by OpenAL.
- **Noise Suppression** - Reduce background noise using RNNoise.
- **Sound Occlusion** - Muffle voices when obstacles are between players.
- **Directional Audio** - Adjust voice volume based on the direction the speaker is facing.
- **Opus Codec** - High-quality voice transmission with low bandwidth usage.
- **Voice Leveling** - Automatically adjusts voice volume toward a configurable target level, keeping different players at a more consistent loudness. A community-requested feature.

### Interface & Controls
- In-game interface for managing voice chat.
- Configurable audio input and output devices.
- Microphone activity and speaking indicators.
- Customizable keybindings, overlay, and audio settings.

### Built-in Add-ons

Unlike the original Plasmo Voice, Plasmo Voice Legacy Forge integrates selected add-on features directly into the mod.<br>
Group Voice Chat is already built in, allowing players to communicate in groups without installing additional modules.<br>
We plan to integrate more add-on features over time. Each built-in feature will have its own configuration options, allowing players and server administrators to enable or disable functionality as needed.<br>

## Download
- [Plasmo Voice Legacy on CurseForge](https://www.curseforge.com/minecraft/mc-mods/plasmo-voice-legacy-forge)
- [Releases](https://github.com/DayleSacoto/plasmo-voice/releases)

## Credits

**Plasmo Voice Legacy Forge** is based on the original [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice) project.

💖 Special thanks to the **Plasmo Voice developers** for creating an excellent open-source voice chat mod and making this backport possible.  
❤️ Thanks to the **GregTech New Horizons community** for keeping Minecraft 1.7.10 alive and inspiring this project.  
🤝 Thanks to everyone who tests the mod, reports bugs, suggests new features, and helps make it better.

*This is an independent community-maintained backport and is not officially affiliated with the original Plasmo Voice team.*