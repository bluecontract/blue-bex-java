plugins {
    id("blue.bex.root-orchestration")
    id("org.jreleaser") version "1.24.0"
}

group = "blue.bex"
version = configuredVersion(
    providers.gradleProperty("bexLocalStageVersion").orNull
)

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.matching {
    it.name in setOf(
        "jreleaserAnnounce",
        "jreleaserDeploy",
        "jreleaserFullRelease",
        "jreleaserPublish",
        "jreleaserRelease",
        "jreleaserUpload"
    )
}.configureEach {
    dependsOn("bexReleaseVerify")
}

if (System.getenv("CI") != null) {
    jreleaser {
        signing {
            active.set(org.jreleaser.model.Active.ALWAYS)
            armored.set(true)
        }
        project {
            description.set("Compiled Java engine for Blue Expression Objects.")
            copyright.set(
                "Copyright 2026 Blue Company. Licensed under the MIT License")
        }
        deploy {
            maven {
                mavenCentral {
                    create("sonatype") {
                        active.set(org.jreleaser.model.Active.ALWAYS)
                        url.set("https://central.sonatype.com/api/v1/publisher")
                        applyMavenCentralRules.set(true)
                        snapshotSupported.set(true)
                        stagingRepository("build/staging-deploy")
                    }
                }
            }
        }
    }
}

fun configuredVersion(localStageVersion: String?): String {
    if (!localStageVersion.isNullOrBlank()) {
        val selected = localStageVersion.trim()
        require(Regex("""\d+\.\d+\.\d+(?:-rc\.\d+)?""")
            .matches(selected)) {
            "bexLocalStageVersion must be a release or RC version"
        }
        return selected
    }
    val configured = Regex("""version\s*=\s*"([^"]+)"""")
        .find(file(".cz.toml").readText())
        ?.groupValues
        ?.get(1)
        ?: "1.0.0"
    return configured + if (System.getenv("CI") == null) "-SNAPSHOT" else ""
}
