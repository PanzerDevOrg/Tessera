# Changelog

Each release section below is published as the changelog on Modrinth and CurseForge.

## [0.2.0] - 2026-10-02

# Tessera v0.2.0 (1.21.1) Changelog

## A rebuilt compression engine: the whole block atlas is now compressed (animations included), with no launch flags, on every Java 21+ install.

> **Requires [Celeris](https://modrinth.com/mod/celeris) 0.1.0 or newer.**

---

## Key Features & Changes

* **In-Place Atlas Compression**: Atlases are re-encoded to BC7 right after vanilla uploads them, with their full mip chain. UVs, chunk meshing, lighting and shaders are untouched, so it works alongside other rendering mods.
* **The Block Atlas Is Compressed Too**: Previously skipped because of animated sprites. Water, lava, fire and every other animation keep playing on the compressed atlas: frames are written as BC blocks, keyframes are cached, and the smallest mip levels are patched block by block.
* **New Native Bridge (JNI, no flags)**: The native encoder now loads on plain Java 21 without `--enable-preview`, and copies nothing between Java and native code. Bundled for **Windows x86_64**, **Linux x86_64** and **Linux AArch64** (glibc 2.17+), built from source in CI.
* **Pure-Java Fallback Encoder**: On any other platform, or if the native library can't load, Tessera keeps compressing with its own Java BC7/BC1 encoder instead of turning off.
* **No More Fringes**: Transparent pixels are colour-bled before encoding, fixing white/grey pixels on seagrass, cutout blocks and some GUI buttons.
* **Off the Render Thread**: Encoding runs in the background; only the GPU upload happens on the render thread, so resource reloads no longer stall the game.
* **Disk Cache**: Compressed atlases are cached on disk (size-capped, least recently used entries evicted), so unchanged atlases aren't re-encoded on the next launch.
* **Developer API**: `AtlasCompressEvent.Pre` (cancel, or choose BC7/BC1 per atlas) and `AtlasCompressEvent.Post` (bytes saved and resident) are now actually fired.

---

## Fixes

* The F3 overlay no longer adds up savings on every reload or quality change (15 MB → 30 MB → 45 MB…).
* The VRAM budget now uses the real GPU memory and tracks Tessera's total, instead of checking each atlas alone.
* The compression quality setting is now applied (it was ignored).
* BC1 flat blocks no longer turn transparent black in some colours.
* Native memory leak on resource reload fixed.
* Changing quality settings no longer reuses stale entries from the disk cache.

---

## Removed

* The `disableAnimationsAtlases` and `dedupSkipDuplicateEncoding` config options (animations no longer need to be frozen to compress an atlas).

## [0.1.0] - 2026-08-07

# Tessera v0.1.0 (1.21.1) Changelog

## First public release of **Tessera**, a high-performance, GPU-first VRAM optimization engine for NeoForge 1.21.1 designed to dramatically reduce the VRAM footprint of texture-heavy modpacks.

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
