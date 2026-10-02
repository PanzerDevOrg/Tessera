![Tessera](https://raw.githubusercontent.com/PanzerDevOrg/Tessera/master/docs/media/banner.png)

**A GPU-first VRAM optimization engine for NeoForge.** Tessera compresses Minecraft's texture atlases into the
GPU-native **BC7** format in place, cutting atlas VRAM by up to **75%** with no visible difference. Animations keep
playing, and it needs no setup.

> ⚠️ **Requires [Celeris](https://modrinth.com/project/sQphaM3I)** (a performance library). Install both.

---

## ✨ Features

|                                 |                                                                                                          |
|---------------------------------|----------------------------------------------------------------------------------------------------------|
| 🧩 **In-place BC7 compression** | Every atlas, including the block atlas, re-encoded right after vanilla uploads it, full mipmaps included |
| 🌊 **Animations keep working**  | Water, lava, fire and every animated texture keep animating on the compressed atlas                      |
| 🎨 **Same look**                | Near-lossless BC7, with cleanup that avoids white fringes on transparent textures                        |
| ⚡ **No stutter**               | Compression runs in the background; resource reloads don't freeze the game                               |
| 💾 **Disk cache**               | Unchanged atlases aren't re-compressed on the next launch                                                |
| 🛡️ **Never crashes the game**   | Native encoder for Windows and Linux, pure-Java fallback everywhere else                                 |
| 📊 **F3 overlay**               | Live VRAM savings in the debug screen                                                                    |

Rendering, UVs, lighting and shaders are untouched, so Tessera works alongside other rendering mods.

## 📈 Results

<div align="center">

| Version 0.1 (Old) | Version 0.2 (New) |
|:---:|:---:|
| <img alt="Old Active Mod Saving 6.13MB" src="https://raw.githubusercontent.com/PanzerDevOrg/Tessera/master/docs/media/old_active_mod.png"> | <img alt="New Active Mod Saving 11.88MB" src="https://raw.githubusercontent.com/PanzerDevOrg/Tessera/master/docs/media/active_mod.png"> |

</div>

Up to **75% less** texture-atlas VRAM. Savings depend on resource packs and mods; they grow with texture-heavy packs.

## 📋 Requirements

|           |                                                         |
|-----------|---------------------------------------------------------|
| Minecraft | 1.21.1                                                  |
| Loader    | NeoForge 21.1.x                                         |
| Java      | 21+                                                     |
| Requires  | [Celeris](https://modrinth.com/project/sQphaM3I) 0.1.0+ |
| GPU       | Any GPU with BC7 support (OpenGL 4.2+)                  |
| Side      | **Client only**                                         |
| macOS     | Not supported (no BC7 on macOS OpenGL)                  |

## ⚡ Maximum performance (optional)

Tessera works without any setup. These JVM flags unlock Celeris's fastest code paths:

| Minecraft (Java)       | Add to your JVM arguments                                                                |
|------------------------|------------------------------------------------------------------------------------------|
| 1.21.x (Java 21)       | `--enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` |
| 26.x and up (Java 25+) | `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED`                  |

**Modrinth App**: instance → *Settings → Java and memory → Java arguments*. Without the flags nothing breaks; each
feature just uses its pure-Java fallback.

## ⚙️ Configuration

In the in-game mod settings, or `config/tessera-client.toml`:

- **Compression quality** (`0`–`7`, default `4`)
- **Disable compression** (keeps atlases vanilla)
- **VRAM budget** (used when your GPU's memory can't be detected)

---

Source, issues and docs: **[github.com/PanzerDevOrg/Tessera](https://github.com/PanzerDevOrg/Tessera)**
· Code: **AGPL-3.0** · Art: **CC BY-NC-SA 4.0** · Made by **Panzer**
