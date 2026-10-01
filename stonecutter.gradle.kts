plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") apply false
}

inline fun <reified T : Any> reqProperty(key: String): T {
    return sc.properties.getOrNull<T>(key)
        ?: error("Required root property '$key' is missing in stonecutter.properties.toml.")
}

stonecutter active "1.21.1"

val modVersion: String = reqProperty("mod.version")
val modId: String = reqProperty("mod.id")

stonecutter parameters {
    swaps["mod_version"] = "\"$modVersion\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    constants["release"] = modId != "template"
}
