@file:Suppress("AvoidDuplicateDependencies")

import com.panzer.gradle.PanzerModExtension

plugins {
    id("panzer.neoforge-mod")
    `maven-publish`
    idea
}

// Celeris (dependency and Maven repositories), natives, neoforge.mods.toml
// expansion, legal files and buildAndCollect come from panzer.neoforge-mod:
// [depends_on.celeris], [natives.tessera_bridge] and [legal] in the TOML.

val modProps = extensions.getByType(PanzerModExtension::class.java).props
val defaultQualityPreset: Long = modProps.req(project, "compression.default_quality_preset").toLong()
val mainSourceSet = sourceSets.main.get()
val generatedResources: File = rootProject.file("src/generated/resources")
val selftestDir = rootProject.file("versions/${modProps.currentVersion}/run-selftest")

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

        // Headless client check of the compressed atlases: TesseraSelfTest.
        register("selftest") {
            client()
            gameDirectory = selftestDir
            systemProperty("tessera.selftest", "true")
            systemProperty("tessera.selftest.version", modProps.currentVersion)
            jvmArgument("-Xmx2G")
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

// A fresh game directory for every check: no saved options (accessibility
// onboarding off, quiet), no stale report.
val prepareSelftest = tasks.register("prepareSelftest") {
    val dir = selftestDir
    doLast {
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("options.txt").writeText(
            listOf("onboardAccessibility:false", "narrator:0", "soundCategory_master:0.0", "renderDistance:2")
                .joinToString("\n", postfix = "\n")
        )
    }
}

tasks.matching { it.name == "runSelftest" }.configureEach {
    dependsOn(prepareSelftest)
    // One game client at a time: they are heavy under software OpenGL.
    val earlier = rootProject.childProjects.keys.filter { it < project.name }.sorted()
    mustRunAfter(earlier.map { ":$it:runSelftest" })
}

tasks.register("selftest") {
    group = "verification"
    description = "Starts a headless client and checks the compressed atlases against their sprites (TesseraSelfTest)."
    dependsOn("runSelftest")
    val report = selftestDir.resolve("tessera-selftest.txt")
    doLast {
        if (!report.isFile) {
            throw GradleException("Tessera self-test: no report in $report (the client stopped before the check)")
        }
        val text = report.readText()
        println(text)
        if (!text.lineSequence().first().endsWith("PASS")) {
            throw GradleException("Tessera self-test failed: see $report")
        }
    }
}
