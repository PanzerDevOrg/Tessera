# docs

Everything published outside GitHub lives here, one file per purpose, so a
release or a page update is a one-file change.

| Path | Published to | When |
|---|---|---|
| `changelogs/<version>.md` | Changelog of that version on Modrinth and CurseForge | On the `v<version>` tag (CI fails if the file is missing) |
| `modrinth/description.md` | The Modrinth project page | On every push to `master` |
| `media/` | Images used by the READMEs (Modrinth loads them from raw.githubusercontent.com) | — |

## Releasing a new version

1. Copy the latest `changelogs/<version>.md` to `changelogs/<new version>.md` and rewrite it, keeping the
   same structure: `# Changelog: Tessera v<version> (<Minecraft>)`, a summary paragraph, `---`,
   `## Key Features & Changes` with `* **Name**: description` bullets, `---`, `## Performance`.
2. Set `version` under `[mod]` in `mod.stonecutter.properties.toml` to the new version.
3. Commit, push, then tag: `git tag v<new version> && git push origin v<new version>`.

CI builds, tests and uploads the jar(s) with this changelog to Modrinth and CurseForge.
