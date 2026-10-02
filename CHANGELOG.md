# Changelog

Each `## [version]` section is published as that release's changelog on Modrinth and CurseForge.

## [0.2.0] - 2026-10-02

# Changelog: Tessera v0.2.0 (1.21.1)

Rebuilt compression engine: every atlas, including the block atlas with its animations, is now compressed to BC7 in place, with no launch flags required. Now requires **Celeris** 0.1.0 or newer.

---

## Key Features & Changes

* **In-Place Atlas Compression**: Atlases are re-encoded to BC7 right after vanilla uploads them, keeping their full mip chain. UVs, chunk meshing, lighting and shaders are untouched.
* **Animated Block Atlas Support**: The block atlas is no longer skipped. Water, lava, fire and every other animated sprite keep animating; frames are written as BC blocks with cached keyframes.
* **New Native Bridge**: The bundled `bc7enc_rdo` encoder now loads through JNI on plain Java 21 (no `--enable-preview`), for **Windows x86_64**, **Linux x86_64** and **Linux AArch64**.
* **Pure-Java Fallback Encoder**: On any other platform, or if the native library can't load, atlases are still compressed by Tessera's own Java encoder.
* **Background Encoding**: Compression runs off the render thread, so resource reloads no longer stall the game.
* **Persistent Disk Cache**: Compressed atlases are cached on disk (size-capped), so unchanged atlases aren't re-encoded on the next launch.
* **Transparency Fix**: Fixed white/grey pixels on seagrass, cutout blocks and some GUI buttons.
* **Debug Overlay Fix**: VRAM savings in the F3 overlay no longer add up on every reload or quality change.
* **Developer API**: `AtlasCompressEvent.Pre` (cancel, or choose BC7/BC1 per atlas) and `AtlasCompressEvent.Post` are now fired.
* **Removed Options**: `disableAnimationsAtlases` and `dedupSkipDuplicateEncoding`, no longer needed.

---

## Performance

* The block atlas, previously left uncompressed, now gets the same up to **75% VRAM reduction** as every other atlas.
* No more frame stalls while atlases are being compressed during resource reloads.

## [0.1.0] - 2026-08-07

# Changelog: Tessera v0.1.0 (1.21.1)

First public release of **Tessera**, a high-performance, GPU-first VRAM optimization engine for NeoForge 1.21.1 designed to dramatically reduce the VRAM footprint of texture-heavy modpacks.

---

## Key Features & Changes

* **On-the-Fly BC7 Compression**: Intercepts Minecraft's `SpriteLoader` atlas stitching pipeline to recompress qualifying texture atlases directly into GPU-native BC7 block-compressed format instead of raw RGBA.
* **Dynamic Auto-Budget Engine**: Implements `VramBudgetEngine` to compute a smart per-atlas VRAM target at runtime, dynamically balancing memory savings against visual fidelity.
* **Dual-OS Native Bridge**: Ships prebuilt, high-performance native binaries for both Windows and Linux (x86_64) using a Rust JNI bridge combined with a vendored C++ BC7 encoder (`bc7enc_rdo`).
* **Safe Automatic Fallback**: Designed with built-in safety checks; if the native bridge fails to load or encounters an unsupported environment, it safely falls back to vanilla RGBA behavior without hard-crashing the game.
* **Smart Caching & Detection**: Utilizes `TextureFamilyDetector` and `AtlasCache` to bypass redundant recompression work across resource reloads.
* **Real-Time F3 Debug Overlay**: Features an integrated `TesseraDebugOverlay` displaying live VRAM savings metrics directly in the debug HUD.
* **Developer API**: Exposes the `TesseraAtlasCompressEvent` public event hook for compatibility and observation by other mods.

---

## Performance

* Achieves up to a **75% reduction** in texture atlas VRAM footprint during heavy workloads (tested extensively on large modpacks like _All The Mods 10_).
* Maintains near-lossless visual quality through optimized BC7 block compression.
