@file:Suppress("AvoidDuplicateDependencies")

import com.panzer.gradle.PanzerModExtension

plugins {
    id("panzer.neoforge-mod")
    `maven-publish`
    idea
}

repositories {
    // Celeris resolution order: a local `publishToMavenLocal` build wins (dev
    // iteration on both mods), otherwise the release published by Celeris CI to
    // GitHub Pages. Both are scoped to Celeris's group so no other dependency is
    // ever looked up in them.
    mavenLocal {
        content { includeGroup("com.panzer.mods") }
    }
    // -PcelerisMavenUrl=<url> points at another Maven tree (a fork, or Celeris's
    // local build/publish-repo to test this path before publishing).
    maven(providers.gradleProperty("celerisMavenUrl").getOrElse("https://panzerdevorg.github.io/Celeris/maven")) {
        name = "CelerisPages"
        content { includeGroup("com.panzer.mods") }
    }
}

val modProps = extensions.getByType(PanzerModExtension::class.java).props
val defaultQualityPreset: Long = modProps.req(project, "compression.default_quality_preset").toLong()
val celerisVersion: String = modProps.req(project, "depends_on.celeris.version")
val mainSourceSet = sourceSets.main.get()
val generatedResources: File = rootProject.file("src/generated/resources")

dependencies {
    implementation("com.panzer.mods:celeris-${modProps.currentVersion}:$celerisVersion")
}

neoForge {
    runs {
        all {
            val runDir = rootProject.file("versions/${modProps.currentVersion}/run")
            if (!runDir.exists()) runDir.mkdirs()

            sourceSet = mainSourceSet
            gameDirectory = runDir

            systemProperty("tessera.compression.defaultQualityPreset", defaultQualityPreset.toString())
        }

        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modProps.modId)
        }

        // Runs DataGenerators (en_us / es_es lang, incl. config-screen keys).
        // Without this run the providers never execute, so the lang JSON is
        // never produced and NeoForge reports every config key as untranslated.
        register("data") {
            data()
            programArguments.addAll(
                "--mod", modProps.modId,
                "--all",
                "--output", generatedResources.absolutePath,
                "--existing", rootProject.file("src/main/resources").absolutePath
            )
        }
    }
}

sourceSets.main {
    resources {
        // Root-relative: this script runs per Stonecutter version project
        // (versions/<mc>/), where a relative path would point at an empty dir.
        srcDir(generatedResources)
    }
}

// ---------------------------------------------------------------------
// Native library staging (tessera_bridge, compiled by native/CMakeLists.txt)
//
// Stages natives/<os>/<arch>/<lib> into the jar as natives/<os>-<arch>/<lib>,
// the layout JniCompressionBackend loads from. Same structure as Celeris's
// collectNatives; only the library name and platform list differ.
// -Ptessera.native.mode=fat|auto|off, -Ptessera.native.target=<os-arch>.
// ---------------------------------------------------------------------

val nativesDir = layout.buildDirectory.dir("generated/natives")

data class NativeTarget(val os: String, val arch: String) {
    val classifier: String = "$os-$arch"
    val relativeSourcePath: String = "$os/$arch"
    val fileName: String = if (os == "windows") "tessera_bridge.dll" else "libtessera_bridge.so"
}

fun target(os: String, vararg architectures: String) =
    architectures.map { arch -> NativeTarget(os, arch) }

// No macOS (OpenGL 4.1 there has no BPTC/BC7). Only the platforms the CI
// builds from source (see .github/workflows/package.yml); other platforms
// use the pure-Java encoder.
val nativeTargets = listOf(
    target("windows", "x86_64"),
    target("linux", "x86_64", "aarch64")
).flatten()

val explicitTarget = providers.gradleProperty("tessera.native.target").orElse("").get()

val nativeMode = run {
    val requestedTasks = gradle.startParameter.taskNames
    val collectRequested = requestedTasks.any { it.endsWith("buildAndCollect") }
    when {
        collectRequested -> "fat"
        else -> project.findProperty("tessera.native.mode")?.toString() ?: "fat"
    }
}

val collectNatives = tasks.register<Copy>("collectNatives") {
    group = "tessera"
    description = "Stages the compiled tessera_bridge native library into /natives, matching the flat <os>-<arch> layout TesseraNativeBridge expects on the classpath"

    val sourceDir = rootProject.projectDir.resolve("natives")

    val targetsToCopy = when {
        explicitTarget.isNotBlank() -> {
            val resolved = when {
                explicitTarget.contains("pc-windows") -> "windows-x86_64"
                explicitTarget.contains("linux") && explicitTarget.startsWith("aarch64") -> "linux-aarch64"
                explicitTarget.contains("linux") -> "linux-x86_64"
                else -> explicitTarget.replace("_", "-")
            }
            nativeTargets.filter { it.classifier == resolved }
        }

        nativeMode == "off" -> emptyList()
        nativeMode == "auto" -> {
            val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows" else "linux"
            val arch = if (System.getProperty("os.arch").lowercase()
                    .let { it.contains("aarch64") || it.contains("arm64") }
            ) "aarch64" else "x86_64"
            nativeTargets.filter { it.classifier == "$os-$arch" }
        }

        else -> nativeTargets // fat (fallback): bundle every supported platform
    }

    // Missing natives policy:
    //  - -Ptessera.native.strict=true (CI release): every bundled platform is required.
    //  - buildAndCollect (local release): the host platform is required, since it can
    //    always be built locally; cross platforms only warn (no cross toolchain here).
    //  - anything else (dev): warn only.
    val strictAll = providers.gradleProperty("tessera.native.strict").orNull.toBoolean()
    val strictHost = gradle.startParameter.taskNames.any { it.endsWith("buildAndCollect") }
    val hostClassifier = run {
        val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows" else "linux"
        val arch = if (System.getProperty("os.arch").lowercase()
                .let { it.contains("aarch64") || it.contains("arm64") }) "aarch64" else "x86_64"
        "$os-$arch"
    }

    val missing = mutableListOf<NativeTarget>()
    targetsToCopy.forEach { target ->
        val srcFile = sourceDir.resolve(target.relativeSourcePath).resolve(target.fileName)
        if (srcFile.exists()) {
            from(srcFile) {
                into("natives/${target.classifier}")
            }
        } else {
            missing += target
        }
    }
    if (missing.isNotEmpty()) {
        val list = missing.joinToString { "${it.classifier} (natives/${it.relativeSourcePath}/${it.fileName})" }
        val message = "Tessera: missing native binaries: $list -- build native/CMakeLists.txt " +
                "for these platforms first; TesseraRuntime will fall back to the pure-Java encoder on them"
        val fatal = strictAll || (strictHost && missing.any { it.classifier == hostClassifier })
        if (fatal) throw GradleException(message) else logger.warn(message)
    }

    into(nativesDir)
}

sourceSets.main {
    resources.srcDir(nativesDir)
}

tasks {
    processResources {
        dependsOn(collectNatives)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val props = buildMap {
            fun registerProp(targetKey: String, tomlKey: String = targetKey) {
                val value = modProps.req(project, tomlKey)
                inputs.property(targetKey, value)
                put(targetKey, value)
            }

            registerProp("loader_version_range", "neoforge.loader_version_range")
            registerProp("mod_license", "mod.license")
            registerProp("mod_id", "mod.id")
            registerProp("mod_version", "mod.version")
            registerProp("mod_name", "mod.name")
            registerProp("mod_authors", "mod.authors")
            registerProp("mod_banner", "mod.banner")
            registerProp("mod_side", "mod.side")
            registerProp("mod_description", "mod.description")
            registerProp("mod_issues", "mod.issues")
            registerProp("neo_version_range")
            registerProp("minecraft_version_range")
            registerProp("celeris_version_range", "depends_on.celeris.version_range")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${modProps.requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        entryCompression = ZipEntryCompression.DEFLATED
        dependsOn(processResources)

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/maven/**")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/INDEX.LIST")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Build mod jar and copy result to `build/libs/{mod version}/`"

        dependsOn("jar")
        dependsOn(publishToMavenLocal)
        from(project.tasks.named("jar"))
        from(project.tasks.named("sourcesJar"))
        inputs.property("version", modProps.modVersion)
        into(rootProject.layout.buildDirectory.file("libs/${modProps.modVersion}"))
    }
}

//tasks.named("build") {
//    dependsOn("publishToMavenLocal")
//}

//publishing {
//    publications {
//        create<MavenPublication>("maven") {
//            from(components["java"])
//            groupId = modProps.modGroup
//            artifactId = "tessera-${modProps.currentVersion}"
//            version = modProps.modVersion
//        }
//    }
//    repositories {
//        mavenLocal()
//    }
//}
