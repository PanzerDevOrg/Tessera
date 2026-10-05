# docs

| Path | Published to | When |
|---|---|---|
| `../README.md` | GitHub, and the Modrinth and CurseForge descriptions (minus `<!-- publish:off -->` blocks) | Modrinth: on each `v*` tag (README pushes only render it). CurseForge: paste the `descriptions` artifact (no API exists) |
| `changelogs/<version>.md` | Changelog of that version on Modrinth, CurseForge and the GitHub release | On the `v<version>` tag (CI fails if the file is missing) |
| `media/` | Images used by the README (made absolute for Modrinth/CurseForge) | With the README |

Publishing is done by `panzer-build-logic/publishing` (see its README): the same tool and workflows for every mod.

## Releasing a new version

1. Copy the latest `changelogs/<version>.md` to `changelogs/<new version>.md` and rewrite it, keeping the
   same structure: `# Changelog: Tessera v<version> (<Minecraft>)`, a summary paragraph, `---`,
   `## Key Features & Changes` with `* **Name**: description` bullets, `---`, `## Performance`.
2. Set `version` under `[mod]` in `mod.stonecutter.properties.toml` to the new version.
3. Check the dry run of the last push (`release-preview` artifact): pages, files and tags as they will be published.
4. Commit, push, then tag: `git tag v<new version> && git push origin v<new version>`.

## Previewing without publishing

- Every push: the **release-preview** artifact of the "Build and Package" run (open `preview.html`).
- Locally: `python3 ../panzer-build-logic/publishing/panzer_publish.py preview --mod . --out /tmp/preview`.
- On demand: *Actions → Build and Package → Run workflow* (dry run by default).
