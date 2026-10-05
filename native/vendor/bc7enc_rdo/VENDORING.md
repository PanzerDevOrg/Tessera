# Vendoring: bc7enc_rdo

Upstream: https://github.com/richgel999/bc7enc_rdo
Pinned commit: `b9438627eef73a1157e84201b6fa6eb2ffd6d9f0` (verified via `git ls-remote` against upstream `master`)
License: dual MIT / public-domain-equivalent, per upstream `LICENSE`. Compatible with linking into Tessera.

## Why vendored, not a Git submodule

`native/CMakeLists.txt` compiles these sources directly into `tessera_bridge`. `fetch.sh` pins the
exact upstream commit and applies two small patches (see below) without adding submodule
management to `git clone`.

## What's actually needed

`bc7enc_rdo` ships a CLI tool, a decoder, ISPC bindings, and a PNG codec that Tessera doesn't use.
The files below are the minimal set that `rdo_bc::rdo_bc_encoder` actually pulls in, confirmed by
grepping `#include` and call sites in the pinned commit, not assumed from the file listing:

- `rdo_bc_encoder.h` / `rdo_bc_encoder.cpp` — the encoder class Tessera's shim drives
- `bc7enc.h` / `bc7enc.cpp` — the underlying BC7 block encoder
- `ert.h` / `ert.cpp` — the rate-distortion (Error Reduction Transform) post-process
- `rgbcx.h` / `rgbcx.cpp`, `rgbcx_table4.h`, `rgbcx_table4_small.h` — shared block-encoding tables
- `utils.h` / `utils.cpp` — `utils::image_u8` / `color_quad_u8`, the RGBA8 container the shim fills
- `bc7decomp.h` / `bc7decomp.cpp` and `bc7decomp_ref.cpp` (shares `bc7decomp.h`, has no header of
  its own) — used internally by the encoder to decode its own output for RDO error scoring
- `dds_defs.h` — shared struct defs pulled in by `utils.h` and `rdo_bc_encoder.h`

Explicitly NOT vendored: `lodepng.*` (PNG file I/O, unused — Tessera passes raw RGBA8 buffers),
`test.cpp` (the upstream CLI's own `main()`, would conflict with `tessera_bridge`'s build),
`bc7e_ispc.h` (ISPC backend; `SUPPORT_BC7E` defaults to `0` upstream, so it's never referenced).

## Patches applied by fetch.sh

- `ert.h`: adds the missing `#include <cstdint>`.
- `utils.cpp`: removes the lodepng/miniz includes and stubs the PNG/deflate helpers (unused).

## Populating this directory

Run `./fetch.sh` from this directory before the first native build (CI runs it before every native
build: `ci_prepare` under `[natives.tessera_bridge]` in `mod.stonecutter.properties.toml`). It checks out the pinned commit into a temp
directory and copies only the files listed above. Only `VENDORING.md`, `fetch.sh`, `LICENSE` and
`tessera_bridge.cpp` are tracked in git; the rest is regenerated and git-ignored.
