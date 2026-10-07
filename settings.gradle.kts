// Managed by panzer-build-logic (shared/mod/settings.gradle.kts): `panzer sync`
// overwrites this file. Everything it does lives in the panzer.settings plugin:
// merging mod.stonecutter.properties.toml with the common TOML, choosing the
// Minecraft versions ([stonecutter], -Pstonecutter.versions=...), the
// Stonecutter tree and early validation.
pluginManagement {
    includeBuild("../panzer-build-logic")

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("panzer.settings")
}
