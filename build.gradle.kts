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

// Only the post-publication metadata invocation may reuse verified evidence.
val metadataOnly = providers.gradleProperty("bexReleaseMetadataOnly").orNull
require(metadataOnly == null || metadataOnly == "true") { "Invalid metadata phase flag" }
if (metadataOnly == "true") {
    require(System.getenv("CI") != null && gradle.startParameter.taskNames ==
        listOf("jreleaserFullRelease", "--exclude-deployer=mavenCentral")) {
        "Metadata phase requires only jreleaserFullRelease with Maven Central excluded"
    }
    tasks.register<Exec>("bexReleaseMetadataGate") {
        commandLine("python3", ".github/scripts/release-ci.py",
            System.getenv("RELEASE_MODE") ?: "invalid", "metadata-check")
    }
    tasks.withType<org.jreleaser.gradle.plugin.tasks.JReleaserFullReleaseTask>().configureEach {
        doFirst {
            require(excludedDeployerTypes.get() == listOf("mavenCentral")) {
                "Metadata phase must exclude the Maven Central deployer"
            }
        }
    }
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
    dependsOn(if (metadataOnly == "true" && name == "jreleaserFullRelease")
        "bexReleaseMetadataGate" else "bexReleaseVerify")
}

// A combined release invocation verifies once, stages all Maven publications,
// then uploads them. Ordering alone does not select publication in verify mode.
tasks.matching { it.name in setOf("jreleaserFullRelease", "jreleaserDeploy") }.configureEach {
    mustRunAfter(
        ":blue-bex-core:publish",
        ":blue-bex-contracts:publish",
        ":blue-bex-java:publish"
    )
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
                        skipPublicationCheck.set(providers.gradleProperty("bexSeparateMavenWait")
                            .map { it == "true" }.orElse(false))
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
        require(Regex(
            """\d+\.\d+\.\d+(?:-rc\.\d+|-dev\.[0-9a-f]{40})?""")
            .matches(selected)) {
            "bexLocalStageVersion must be a release, RC, or " +
                "commit-bound development version"
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
