pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "blue-bex-java"

include(
    ":blue-bex-core",
    ":blue-bex-contracts",
    ":blue-bex-conformance",
    ":blue-bex-java",
    ":examples"
)

val compositePath = providers.gradleProperty("blueLanguageCompositePath")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val stagedRepositoryPath = providers.gradleProperty("blueLanguageRepository")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val publishedOnlyTasks = setOf(
    "bexPublishedDependencyVerification",
    "bexPublishedLanguageVerification",
    "bexWorkingVerification",
    "generateBexWorkingReport",
    "bexModernizationVerification",
    "generateBexModernizationReport",
    "bexReleaseVerify",
    "generateBexReleaseReport",
    "bexReleaseEvidence",
    "publish",
    "jreleaserFullRelease"
)
val stagedPublicationRequested = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .any {
        it.startsWith("publish") && it.endsWith("ToStagingRepository")
    }
val requestedPublishedOnlyTask = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .firstOrNull {
        it in publishedOnlyTasks ||
            (it.startsWith("publish") && !stagedPublicationRequested) ||
            it.startsWith("jreleaser")
    }
if (requestedPublishedOnlyTask != null) {
    require(compositePath == null) {
        "$requestedPublishedOnlyTask is published-only and forbids " +
            "blueLanguageCompositePath"
    }
    require(stagedRepositoryPath == null) {
        "$requestedPublishedOnlyTask is published-only and forbids " +
            "blueLanguageRepository"
    }
}

if (compositePath != null) {
    val checkout = file(compositePath)
    require(checkout.isDirectory) {
        "blueLanguageCompositePath is not a directory: ${checkout.absolutePath}"
    }
    require(
        file("${checkout.path}/settings.gradle.kts").isFile ||
            file("${checkout.path}/settings.gradle").isFile
    ) {
        "blueLanguageCompositePath is not a Gradle build: ${checkout.absolutePath}"
    }

    val required = listOf(
        "blue-language-model",
        "blue-language-core",
        "blue-language-mapping",
        "blue-contracts-core",
        "blue-language-java"
    )
    val missing = required.filter { name ->
        val projectDirectory = file("${checkout.path}/$name")
        !projectDirectory.isDirectory ||
            (!file("${projectDirectory.path}/build.gradle.kts").isFile &&
                !file("${projectDirectory.path}/build.gradle").isFile)
    }
    require(missing.isEmpty()) {
        "blueLanguageCompositePath is missing required projects: " +
            missing.joinToString(", ")
    }

    includeBuild(checkout) {
        dependencySubstitution {
            substitute(module("blue.language:blue-language-model"))
                .using(project(":blue-language-model"))
            substitute(module("blue.language:blue-language-core"))
                .using(project(":blue-language-core"))
            substitute(module("blue.language:blue-language-mapping"))
                .using(project(":blue-language-mapping"))
            substitute(module("blue.language:blue-contracts-core"))
                .using(project(":blue-contracts-core"))
            substitute(module("blue.language:blue-language-java"))
                .using(project(":blue-language-java"))
        }
    }
}
